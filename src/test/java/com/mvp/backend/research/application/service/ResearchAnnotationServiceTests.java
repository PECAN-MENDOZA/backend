package com.mvp.backend.research.application.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AgreementSummary;
import com.mvp.backend.research.application.dto.AnnotationBatchResponse;
import com.mvp.backend.research.application.dto.AnnotationCsvFile;
import com.mvp.backend.research.domain.model.AnnotationBatch;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationItem;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;
import com.mvp.backend.research.domain.model.ProtocolTask;
import com.mvp.backend.research.domain.model.ResearchAuditEvent;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;
import com.mvp.backend.research.domain.repository.AnnotationBatchRepository;
import com.mvp.backend.research.domain.repository.AnnotationImportRepository;
import com.mvp.backend.research.domain.repository.AnnotationItemRepository;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ResearchAnnotationServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final Pattern SAMPLE_CODE = Pattern.compile("T-[A-HJ-NP-Z2-9]{8}");

    @Mock
    private ResearchStudyRepository studyRepository;
    @Mock
    private ExperimentRunRepository runRepository;
    @Mock
    private CorrectionSessionRepository sessionRepository;
    @Mock
    private AnnotationBatchRepository batchRepository;
    @Mock
    private AnnotationItemRepository itemRepository;
    @Mock
    private AnnotationImportRepository importRepository;
    @Mock
    private ResearchAuditEventRepository auditRepository;
    @Mock
    private ResearcherRepository researcherRepository;

    private ResearchAnnotationService service;
    private Researcher researcher;
    private UUID researcherId;
    private ResearchStudy study;
    private UUID studyId;
    private StudyProtocol protocol;
    private Student student;

    @BeforeEach
    void setUp() {
        service = new ResearchAnnotationService(
                studyRepository, runRepository, sessionRepository, batchRepository, itemRepository,
                importRepository, auditRepository, researcherRepository, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        researcher = new Researcher("lab@example.edu", "hash");
        researcherId = researcher.getId();
        study = new ResearchStudy("EXP-01", "Teclado predictivo", researcher);
        study.activate();
        studyId = study.getId();
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Consigna A");
        protocol.addTask(TaskVariant.TASK_B, "Consigna B");
        protocol.activate();
        student = new Student("student-real-name", "Colegio", "hash");
    }

    // ------------------------------------------------------------------ export

    @Test
    void orthographyExportContainsNoConditionOrParticipant() {
        ExperimentRun assisted = completedRun(1, ExperimentCondition.ASSISTED, "Hola, mundo \"con comillas\"");
        ExperimentRun unassisted = completedRun(2, ExperimentCondition.UNASSISTED, "texto sin\nayuda");
        ExperimentRun excluded = completedRun(3, ExperimentCondition.ASSISTED, "TEXTO EXCLUIDO");
        excluded.exclude("Excluded for a documented reason", researcher, NOW);
        ExperimentRun pending = pendingRun(4, ExperimentCondition.ASSISTED);
        stubOwnedStudy();
        stubResearcher();
        when(runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId))
                .thenReturn(List.of(assisted, unassisted, excluded, pending));
        stubBatchPersistence();

        AnnotationBatchResponse batch = service.createBatch(researcherId, studyId, AnnotationKind.ORTHOGRAPHY);
        List<AnnotationItem> items = savedItems();
        AnnotationBatch saved = savedBatch();
        when(batchRepository.findByIdAndStudyId(batch.id(), studyId)).thenReturn(Optional.of(saved));
        when(itemRepository.findByBatchIdOrderByPositionAsc(batch.id())).thenReturn(items);
        AnnotationCsvFile file = service.export(researcherId, studyId, batch.id());
        String csv = new String(file.bytes(), UTF_8);

        assertThat(batch.kind()).isEqualTo(AnnotationKind.ORTHOGRAPHY);
        assertThat(batch.rowCount()).isEqualTo(2);
        assertThat(batch.columns()).containsExactly("sample_code", "text", "score");
        assertThat(csv).startsWith("sample_code,text,score\n");
        assertThat(csv).contains("\"Hola, mundo \"\"con comillas\"\"\",\n").contains("\"texto sin\nayuda\",\n");
        assertThat(csv).doesNotContain("ASSISTED", "UNASSISTED", "P-001", "P-002", "student", "TEXTO EXCLUIDO",
                assisted.getId().toString(), unassisted.getId().toString());
        assertThat(items).extracting(AnnotationItem::getSampleCode).doesNotHaveDuplicates()
                .allMatch(code -> SAMPLE_CODE.matcher(code).matches());
        assertThat(items).extracting(AnnotationItem::getPosition).containsExactly(0, 1);
        assertThat(Pattern.compile("(?m)^T-[A-HJ-NP-Z2-9]{8},").matcher(csv).results().count())
                .as("every data row starts with its sample code").isEqualTo(2);
        assertThat(batch.exportSha256()).isEqualTo(sha256(file.bytes()));
        assertThat(file.filename()).endsWith(".csv").doesNotContain("EXP-01");
        assertThat(batch.agreement().complete()).isFalse();
        assertThat(batch.agreement().weightedKappa()).isNull();
        assertThat(batch.completedSlots()).isEmpty();

        ArgumentCaptor<ResearchAuditEvent> events = ArgumentCaptor.forClass(ResearchAuditEvent.class);
        verify(auditRepository, times(2)).save(events.capture());
        assertThat(events.getAllValues()).extracting(ResearchAuditEvent::getAction)
                .containsExactly("ANNOTATION_BATCH_CREATED", "ANNOTATION_BATCH_EXPORTED");
        assertThat(events.getAllValues()).allSatisfy(event -> assertThat(String.valueOf(event.getDetail()))
                .contains(batch.exportSha256())
                .doesNotContain("Hola", "P-001", "ASSISTED", items.get(0).getSampleCode()));
    }

    @Test
    void semanticExportEvaluatesTheSelectedSuggestionOrTheFirstOffered() {
        ExperimentRun assisted = completedRun(1, ExperimentCondition.ASSISTED, "final");
        ExperimentRun unassisted = completedRun(2, ExperimentCondition.UNASSISTED, "final sin ayuda");
        CorrectionSession accepted = session(assisted, "ola mundo", "hola mundo", List.of("Hola mundo", "hola, mundo"));
        accepted.registerFeedback("hola, mundo", null, true, 1, null);
        CorrectionSession rejected = session(assisted, "ke tal", "que tal", List.of("que tal?"));
        rejected.registerFeedback(null, null, false, 0, "UNDO");
        CorrectionSession unanswered = session(assisted, "asta luego", "hasta luego", List.of());
        CorrectionSession empty = session(assisted, "sin sugerencias", "", List.of());
        stubOwnedStudy();
        stubResearcher();
        when(runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId)).thenReturn(List.of(assisted, unassisted));
        when(sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(List.of(assisted.getId(), unassisted.getId())))
                .thenReturn(List.of(accepted, rejected, unanswered, empty));
        stubBatchPersistence();

        AnnotationBatchResponse batch = service.createBatch(researcherId, studyId, AnnotationKind.SEMANTIC);
        List<AnnotationItem> items = savedItems();
        AnnotationBatch saved = savedBatch();
        when(batchRepository.findByIdAndStudyId(batch.id(), studyId)).thenReturn(Optional.of(saved));
        when(itemRepository.findByBatchIdOrderByPositionAsc(batch.id())).thenReturn(items);
        String csv = new String(service.export(researcherId, studyId, batch.id()).bytes(), UTF_8);

        assertThat(batch.rowCount()).isEqualTo(3);
        assertThat(batch.columns()).containsExactly("sample_code", "original_text", "suggestion", "score");
        assertThat(csv).startsWith("sample_code,original_text,suggestion,score\n");
        assertThat(csv).contains(",ola mundo,\"hola, mundo\",\n")
                .contains(",ke tal,que tal,\n")
                .contains(",asta luego,hasta luego,\n")
                .doesNotContain("sin sugerencias", "final sin ayuda", "ASSISTED", "P-00", "que tal?");
        assertThat(items).extracting(AnnotationItem::getSuggestionIndex).containsExactlyInAnyOrder(2, 0, 0);
        assertThat(items).allMatch(item -> item.getRun() != null && item.getCorrectionSession() != null);
    }

    @Test
    void batchRequiresCompletedRunsAndAnOwnedStudy() {
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createBatch(researcherId, studyId, AnnotationKind.ORTHOGRAPHY))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Study not found");

        stubOwnedStudy();
        stubResearcher();
        when(runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId))
                .thenReturn(List.of(pendingRun(1, ExperimentCondition.ASSISTED)));
        assertThatThrownBy(() -> service.createBatch(researcherId, studyId, AnnotationKind.ORTHOGRAPHY))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Study has no completed runs to annotate");
        verify(batchRepository, never()).save(any());
    }

    @Test
    void exportRefusesABatchWhoseContentNoLongerMatchesItsHash() {
        AnnotationBatch batch = new AnnotationBatch(study, AnnotationKind.ORTHOGRAPHY, researcher, NOW);
        batch.freeze(1, "0".repeat(64));
        stubOwnedStudy();
        stubResearcher();
        when(batchRepository.findByIdAndStudyId(batch.getId(), studyId)).thenReturn(Optional.of(batch));
        when(itemRepository.findByBatchIdOrderByPositionAsc(batch.getId())).thenReturn(List.of(
                new AnnotationItem(batch, "T-AAAAAAAA", 0, completedRun(1, ExperimentCondition.ASSISTED, "x"), null, null)));

        assertThatThrownBy(() -> service.export(researcherId, studyId, batch.getId()))
                .isInstanceOf(ConflictException.class);
        verify(auditRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ import

    @Test
    void importRejectsUnknownSampleCode() {
        Fixture fixture = orthographyBatch("uno", "dos");
        stubResearcher();

        assertThatThrownBy(() -> service.importScores(researcherId, studyId, fixture.batch().getId(),
                AnnotationSlot.RATER_1, "Ana", "sample_code,score\nT-UNKNOWN1,1\n".getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown sample code")
                .hasMessageContaining("T-UNKNOWN1");
        verify(importRepository, never()).saveAndFlush(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void importValidatesHeaderDuplicatesMissingRowsAndScores() {
        Fixture fixture = orthographyBatch("uno", "dos");
        stubResearcher();
        String a = fixture.codes().get(0);
        String b = fixture.codes().get(1);

        assertThatImportFails(fixture, "sample_code,puntaje\n" + a + ",1\n" + b + ",2\n").hasMessageContaining("header");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",1\n" + a + ",2\n").hasMessageContaining("Duplicate sample code");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",1\n").hasMessageContaining("Missing scores")
                .hasMessageContaining(b);
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",1\n" + b + ",\n").hasMessageContaining("Missing score");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",-1\n" + b + ",2\n").hasMessageContaining("non-negative integer");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",1.5\n" + b + ",2\n").hasMessageContaining("non-negative integer");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",1\n" + b + ",2,extra\n").hasMessageContaining("columns");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",1\n" + b + ",2\u0000\n").hasMessageContaining("NUL");
        assertThatImportFails(fixture, "").hasMessageContaining("empty");
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",\"1\n").hasMessageContaining("quote");
        verify(importRepository, never()).saveAndFlush(any());
    }

    @Test
    void importAcceptsTheExportedLayoutWithScoresFilledInAndABom() {
        Fixture fixture = orthographyBatch("Hola, mundo", "dos");
        String a = fixture.codes().get(0);
        String b = fixture.codes().get(1);
        String csv = "\uFEFFsample_code,text,score\r\n" + a + ",\"Hola, mundo\",3\r\n" + b + ",dos, 0 \r\n";
        stubImportPersistence();

        AnnotationBatchResponse response = service.importScores(researcherId, studyId, fixture.batch().getId(),
                AnnotationSlot.RATER_1, "  Ana ", csv.getBytes(UTF_8));

        assertThat(fixture.items().get(0).getRater1Score()).isEqualTo(3);
        assertThat(fixture.items().get(1).getRater1Score()).isZero();
        assertThat(response.imports()).hasSize(1);
        assertThat(response.imports().get(0).slot()).isEqualTo(AnnotationSlot.RATER_1);
        assertThat(response.imports().get(0).rater()).isEqualTo("Ana");
        assertThat(response.imports().get(0).version()).isEqualTo(1);
        assertThat(response.imports().get(0).current()).isTrue();
        assertThat(response.imports().get(0).fileSha256()).isEqualTo(sha256(csv.getBytes(UTF_8)));
        assertThat(response.completedSlots()).containsExactly(AnnotationSlot.RATER_1);
        assertThat(response.agreement().complete()).isFalse();
    }

    @Test
    void semanticImportOnlyAcceptsZeroToTwo() {
        Fixture fixture = semanticBatch();
        String a = fixture.codes().get(0);
        String b = fixture.codes().get(1);

        stubImportPersistence();
        assertThatImportFails(fixture, "sample_code,score\n" + a + ",3\n" + b + ",2\n").hasMessageContaining("0, 1 or 2");
        service.importScores(researcherId, studyId, fixture.batch().getId(), AnnotationSlot.ADJUDICATED, "Consenso",
                ("sample_code,score\n" + a + ",2\n" + b + ",0\n").getBytes(UTF_8));
        assertThat(fixture.items().get(0).getAdjudicatedScore()).isEqualTo(2);
        assertThat(fixture.items().get(1).getAdjudicatedScore()).isZero();
        assertThat(fixture.items().get(0).getRater1Score()).isNull();
    }

    @Test
    void ratersMustBeDistinctAndTheSameFileCannotBeImportedTwice() {
        Fixture fixture = orthographyBatch("uno", "dos");
        stubResearcher();
        String csv = "sample_code,score\n" + fixture.codes().get(0) + ",1\n" + fixture.codes().get(1) + ",0\n";
        AnnotationImport first = new AnnotationImport(fixture.batch(), AnnotationSlot.RATER_1, 1, "Ana",
                sha256(csv.getBytes(UTF_8)), csv, researcher, NOW);
        when(importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(fixture.batch().getId(), AnnotationSlot.RATER_1))
                .thenReturn(Optional.of(first));
        when(importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(fixture.batch().getId(), AnnotationSlot.RATER_2))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importScores(researcherId, studyId, fixture.batch().getId(),
                AnnotationSlot.RATER_2, "ana", csv.getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("distinct");

        when(importRepository.existsByBatchIdAndSlotAndFileSha256(fixture.batch().getId(), AnnotationSlot.RATER_1,
                sha256(csv.getBytes(UTF_8)))).thenReturn(true);
        assertThatThrownBy(() -> service.importScores(researcherId, studyId, fixture.batch().getId(),
                AnnotationSlot.RATER_1, "Ana", csv.getBytes(UTF_8)))
                .isInstanceOf(ConflictException.class);
        verify(importRepository, never()).saveAndFlush(any());
    }

    @Test
    void reimportSupersedesThePreviousImportWithoutDeletingIt() {
        Fixture fixture = orthographyBatch("uno", "dos");
        String a = fixture.codes().get(0);
        String b = fixture.codes().get(1);
        String v1 = "sample_code,score\n" + a + ",1\n" + b + ",0\n";
        AnnotationImport previous = new AnnotationImport(fixture.batch(), AnnotationSlot.RATER_2, 1, "Beto",
                sha256(v1.getBytes(UTF_8)), v1, researcher, NOW.minus(Duration.ofDays(1)));
        fixture.items().get(0).record(AnnotationSlot.RATER_2, 1);
        fixture.items().get(1).record(AnnotationSlot.RATER_2, 0);
        when(importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(fixture.batch().getId(), AnnotationSlot.RATER_2))
                .thenReturn(Optional.of(previous));
        when(importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(fixture.batch().getId(), AnnotationSlot.RATER_1))
                .thenReturn(Optional.empty());
        when(importRepository.findFirstByBatchIdAndSlotOrderByVersionDesc(fixture.batch().getId(), AnnotationSlot.RATER_2))
                .thenReturn(Optional.of(previous));
        stubImportPersistence();
        String v2 = "sample_code,score\n" + a + ",2\n" + b + ",0\n";

        service.importScores(researcherId, studyId, fixture.batch().getId(), AnnotationSlot.RATER_2, "Beto",
                v2.getBytes(UTF_8));

        ArgumentCaptor<AnnotationImport> saved = ArgumentCaptor.forClass(AnnotationImport.class);
        verify(importRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(2);
        assertThat(saved.getValue().getContent()).isEqualTo(v2);
        assertThat(saved.getValue().isCurrent()).isTrue();
        assertThat(previous.getSupersededAt()).isEqualTo(NOW);
        assertThat(previous.getContent()).isEqualTo(v1);
        assertThat(fixture.items().get(0).getRater2Score()).isEqualTo(2);
        verify(importRepository, never()).delete(any());
        verify(importRepository, never()).deleteById(any());
        ArgumentCaptor<ResearchAuditEvent> event = ArgumentCaptor.forClass(ResearchAuditEvent.class);
        verify(auditRepository).save(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("ANNOTATION_IMPORTED");
        assertThat(event.getValue().getDetail()).contains("slot=RATER_2", "version=2", sha256(v2.getBytes(UTF_8)), "rater=Beto")
                .doesNotContain(a, b, "uno", "dos");
    }

    // --------------------------------------------------------------- agreement

    @Test
    void agreementIsUnavailableUntilBothRatersAreComplete() {
        Fixture fixture = orthographyBatch("uno", "dos", "tres");
        fixture.items().forEach(item -> item.record(AnnotationSlot.RATER_1, 1));
        fixture.items().get(0).record(AnnotationSlot.RATER_2, 1);

        AgreementSummary summary = service.getBatch(researcherId, studyId, fixture.batch().getId()).agreement();

        assertThat(summary.complete()).isFalse();
        assertThat(summary.weightedKappa()).isNull();
        assertThat(summary.exactAgreement()).isNull();
    }

    @Test
    void agreementUsesLinearlyWeightedKappaAndExactAgreement() {
        assertThat(InterRaterAgreement.of(List.of(0, 1, 2, 2), List.of(0, 1, 2, 2)))
                .isEqualTo(new AgreementSummary(true, 1.0, 1.0));
        // Sin varianza en ambos vectores: kappa indefinido, acuerdo exacto total.
        assertThat(InterRaterAgreement.of(List.of(1, 1, 1), List.of(1, 1, 1)))
                .isEqualTo(new AgreementSummary(true, null, 1.0));
        // Pesos lineales sobre categorias 0..2: un desacuerdo de distancia 1 y uno de distancia 2.
        AgreementSummary partial = InterRaterAgreement.of(List.of(0, 1, 2, 0, 2), List.of(0, 2, 2, 2, 2));
        assertThat(partial.exactAgreement()).isCloseTo(0.6, within(1e-9));
        // Do = (0+1+0+2+0)/(5*2) = 0.3 ; De = sum p1(a)p2(b)|a-b|/2 con p1=(.4,.2,.4) p2=(.2,0,.8)
        //    = (0.64 + 0.04 + 0.16 + 0.16) / 2 = 0.5 ; kappa = 1 - 0.3/0.5 = 0.4
        assertThat(partial.weightedKappa()).isCloseTo(0.4, within(1e-9));
        // Conteos ortograficos: la escala se toma del rango observado (0..3).
        assertThat(InterRaterAgreement.of(List.of(0, 3, 1), List.of(0, 3, 1)).weightedKappa()).isCloseTo(1.0, within(1e-9));
        // Dos evaluadores constantes en valores distintos: acuerdo observado igual al esperado, kappa 0.
        assertThat(InterRaterAgreement.of(List.of(1, 1), List.of(2, 2)).weightedKappa()).isCloseTo(0.0, within(1e-9));
        assertThatThrownBy(() -> InterRaterAgreement.of(List.of(1), List.of(1, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchSummaryExposesAgreementOnceBothRatersAreImported() {
        Fixture fixture = orthographyBatch("uno", "dos", "tres", "cuatro");
        List<Integer> r1 = List.of(0, 1, 2, 3);
        List<Integer> r2 = List.of(0, 1, 2, 1);
        for (int i = 0; i < 4; i++) {
            fixture.items().get(i).record(AnnotationSlot.RATER_1, r1.get(i));
            fixture.items().get(i).record(AnnotationSlot.RATER_2, r2.get(i));
        }

        AnnotationBatchResponse response = service.getBatch(researcherId, studyId, fixture.batch().getId());

        assertThat(response.completedSlots()).containsExactly(AnnotationSlot.RATER_1, AnnotationSlot.RATER_2);
        assertThat(response.agreement()).isEqualTo(InterRaterAgreement.of(r1, r2));
        assertThat(response.agreement().complete()).isTrue();
        assertThat(response.agreement().exactAgreement()).isCloseTo(0.75, within(1e-9));
        assertThat(response.agreement().weightedKappa()).isNotNull();
    }

    // ----------------------------------------------------------------- helpers

    private record Fixture(AnnotationBatch batch, List<AnnotationItem> items, List<String> codes) {
    }

    private Fixture orthographyBatch(String... texts) {
        AnnotationBatch batch = new AnnotationBatch(study, AnnotationKind.ORTHOGRAPHY, researcher, NOW);
        List<AnnotationItem> items = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            ExperimentRun run = completedRun(i + 1, ExperimentCondition.ASSISTED, texts[i]);
            items.add(new AnnotationItem(batch, "T-ORTHOGR" + (char) ('A' + i), i, run, null, null));
        }
        return fixture(batch, items);
    }

    private Fixture semanticBatch() {
        AnnotationBatch batch = new AnnotationBatch(study, AnnotationKind.SEMANTIC, researcher, NOW);
        ExperimentRun run = completedRun(1, ExperimentCondition.ASSISTED, "final");
        CorrectionSession first = session(run, "ola", "hola", List.of());
        CorrectionSession second = session(run, "ke", "que", List.of());
        List<AnnotationItem> items = List.of(
                new AnnotationItem(batch, "T-SEMANTC2", 0, run, first, 0),
                new AnnotationItem(batch, "T-SEMANTC3", 1, run, second, 0));
        return fixture(batch, items);
    }

    private Fixture fixture(AnnotationBatch batch, List<AnnotationItem> items) {
        batch.freeze(items.size(), "f".repeat(64));
        stubOwnedStudy();
        when(batchRepository.findByIdAndStudyId(batch.getId(), studyId)).thenReturn(Optional.of(batch));
        when(itemRepository.findByBatchIdOrderByPositionAsc(batch.getId())).thenReturn(items);
        return new Fixture(batch, items, items.stream().map(AnnotationItem::getSampleCode).toList());
    }

    private void stubBatchPersistence() {
        when(batchRepository.save(any(AnnotationBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.saveAll(anyCollection()))
                .thenAnswer(inv -> new ArrayList<>(inv.<List<AnnotationItem>>getArgument(0)));
    }

    /** Simula la persistencia: lo guardado con saveAndFlush aparece luego en el historial del lote. */
    private void stubImportPersistence() {
        stubResearcher();
        List<AnnotationImport> stored = new ArrayList<>();
        when(importRepository.saveAndFlush(any(AnnotationImport.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(importRepository.findByBatchIdOrderByVersionAsc(any())).thenAnswer(inv -> List.copyOf(stored));
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertThatImportFails(Fixture fixture, String csv) {
        return assertThatThrownBy(() -> service.importScores(researcherId, studyId, fixture.batch().getId(),
                AnnotationSlot.RATER_1, "Ana", csv.getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class);
    }

    private void stubOwnedStudy() {
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
    }

    private void stubResearcher() {
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
    }

    @SuppressWarnings("unchecked")
    private List<AnnotationItem> savedItems() {
        ArgumentCaptor<List<AnnotationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private AnnotationBatch savedBatch() {
        ArgumentCaptor<AnnotationBatch> captor = ArgumentCaptor.forClass(AnnotationBatch.class);
        verify(batchRepository).save(captor.capture());
        return captor.getValue();
    }

    private ExperimentRun pendingRun(int participantNumber, ExperimentCondition condition) {
        StudyParticipant participant = new StudyParticipant(study, participantNumber);
        participant.linkStudent(student);
        ProtocolTask task = protocol.findTask(TaskVariant.TASK_A).orElseThrow();
        return new ExperimentRun(participant, protocol, task, condition, "a".repeat(64), NOW.plusSeconds(600), NOW);
    }

    private ExperimentRun completedRun(int participantNumber, ExperimentCondition condition, String finalText) {
        ExperimentRun run = pendingRun(participantNumber, condition);
        run.redeem(NOW);
        run.start(NOW);
        run.complete(finalText, 60_000, UUID.randomUUID(), NOW.plusSeconds(60));
        return run;
    }

    private CorrectionSession session(ExperimentRun run, String original, String corrected, List<String> alternatives) {
        CorrectionSession session = new CorrectionSession(student, original, run);
        String json = "[" + String.join(",", alternatives.stream().map(s -> "\"" + s + "\"").toList()) + "]";
        session.complete(corrected, 0, json, 10L);
        return session;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
