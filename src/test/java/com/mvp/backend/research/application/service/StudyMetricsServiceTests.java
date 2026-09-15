package com.mvp.backend.research.application.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.config.ResearchProperties;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AnnotationCsvFile;
import com.mvp.backend.research.application.dto.PairValue;
import com.mvp.backend.research.application.dto.PairedSummary;
import com.mvp.backend.research.application.dto.RunMetrics;
import com.mvp.backend.research.application.dto.StudyResultsResponse;
import com.mvp.backend.research.domain.model.AnnotationBatch;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationItem;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;
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
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StudyMetricsServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final double EPS = 1e-6;

    @Mock
    private ResearchStudyRepository studyRepository;
    @Mock
    private StudyParticipantRepository participantRepository;
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

    private StudyMetricsService service;
    private Researcher researcher;
    private UUID researcherId;
    private ResearchStudy study;
    private UUID studyId;
    private StudyProtocol protocol;
    private Student student;
    private final List<StudyParticipant> participants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = newService(new ResearchProperties(Duration.ofMinutes(30), null, null));
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

    private StudyMetricsService newService(ResearchProperties properties) {
        return new StudyMetricsService(studyRepository, participantRepository, runRepository, sessionRepository,
                batchRepository, itemRepository, importRepository, auditRepository, researcherRepository,
                new ObjectMapper(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- formulas

    @Test
    void calculatesRunPeoAndPpm() {
        RunMetrics metrics = StudyMetricsService.runMetrics("uno dos tres cuatro", 120_000, 1);
        assertThat(metrics.wordCount()).isEqualTo(4);
        assertThat(metrics.peo()).isEqualTo(25.0);
        assertThat(metrics.ppm()).isEqualTo(2.0);
    }

    @Test
    void runWithoutCountableWordsHasNoPeo() {
        RunMetrics metrics = StudyMetricsService.runMetrics("... !!!", 60_000, 0);
        assertThat(metrics.wordCount()).isZero();
        assertThat(metrics.peo()).isNull();
        assertThat(metrics.ppm()).isZero();
    }

    @Test
    void countsUnicodeWordsWithApostrophesAndHyphensAsOneWord() {
        // El | niño | comió | 2 | manzanas | no | l'amour | re-hacer | x  -> 9 (the underscores are not word chars)
        assertThat(StudyMetricsService.wordCount("El niño comió 2 manzanas, ¿no? l'amour re-hacer _x_")).isEqualTo(9);
        assertThat(StudyMetricsService.wordCount("  cuatro   cinco ")).isEqualTo(2);
        assertThat(StudyMetricsService.wordCount("a - b")).isEqualTo(2);
        assertThat(StudyMetricsService.wordCount("")).isZero();
        assertThat(StudyMetricsService.wordCount("   ")).isZero();
        assertThat(StudyMetricsService.wordCount(null)).isZero();
    }

    @Test
    void deltaUsesAssistedMinusUnassisted() {
        PairedSummary result = StudyMetricsService.pairedSummary(List.of(
                new PairValue(10.0, 20.0),
                new PairValue(15.0, 25.0)));
        assertThat(result.meanDelta()).isEqualTo(-10.0);
        assertThat(result.n()).isEqualTo(2);
        assertThat(result.assistedMean()).isEqualTo(12.5);
        assertThat(result.unassistedMean()).isEqualTo(22.5);
        // Both differences are identical: zero variance, no inference possible.
        assertThat(result.sdDelta()).isEqualTo(0.0);
        assertThat(result.ci95Lower()).isNull();
        assertThat(result.ci95Upper()).isNull();
        assertThat(result.pValue()).isNull();
        assertThat(result.cohenDz()).isNull();
    }

    @Test
    void pairedSummaryUsesTheTDistributionForCiPValueAndCohenDz() {
        // deltas = [-4, -6, -2, -8]: mean -5, sd sqrt(20/3) = 2.581989, se 1.290994, t(0.975, 3) = 3.182446
        PairedSummary result = StudyMetricsService.pairedSummary(List.of(
                new PairValue(6.0, 10.0),
                new PairValue(4.0, 10.0),
                new PairValue(8.0, 10.0),
                new PairValue(2.0, 10.0)));
        assertThat(result.n()).isEqualTo(4);
        assertThat(result.assistedMean()).isEqualTo(5.0);
        assertThat(result.unassistedMean()).isEqualTo(10.0);
        assertThat(result.meanDelta()).isEqualTo(-5.0);
        assertThat(result.sdDelta()).isCloseTo(2.581988897, within(EPS));
        assertThat(result.ci95Lower()).isCloseTo(-9.108520514, within(EPS));
        assertThat(result.ci95Upper()).isCloseTo(-0.891479486, within(EPS));
        assertThat(result.tStatistic()).isCloseTo(-3.872983346, within(EPS));
        assertThat(result.pValue()).isCloseTo(0.030466292, within(EPS));
        assertThat(result.cohenDz()).isCloseTo(-1.936491673, within(EPS));
    }

    @Test
    void singlePairIsDescriptiveOnly() {
        PairedSummary result = StudyMetricsService.pairedSummary(List.of(new PairValue(3.0, 7.0)));
        assertThat(result.n()).isEqualTo(1);
        assertThat(result.meanDelta()).isEqualTo(-4.0);
        assertThat(result.sdDelta()).isNull();
        assertThat(result.ci95Lower()).isNull();
        assertThat(result.pValue()).isNull();
        assertThat(result.cohenDz()).isNull();

        PairedSummary empty = StudyMetricsService.pairedSummary(List.of());
        assertThat(empty.n()).isZero();
        assertThat(empty.meanDelta()).isNull();
    }

    @Test
    void relativeReductionIsNotComputedWhenUnassistedPeoIsZero() {
        assertThat(StudyMetricsService.relativeReduction(20.0, 15.0)).isEqualTo(25.0);
        assertThat(StudyMetricsService.relativeReduction(40.0, 50.0)).isEqualTo(-25.0);
        assertThat(StudyMetricsService.relativeReduction(0.0, 5.0)).isNull();
        assertThat(StudyMetricsService.relativeReduction(0.0, 0.0)).isNull();
    }

    // ---------------------------------------------------------------- sampling

    @Test
    void resultsUseOnlyCompletePairsOfCompletedNonExcludedRuns() {
        ExperimentRun p1Assisted = completedRun(1, ExperimentCondition.ASSISTED, "uno dos tres cuatro", 120_000);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, "uno dos tres cuatro cinco", 60_000);
        ExperimentRun p2Assisted = completedRun(2, ExperimentCondition.ASSISTED, "solo asistido", 60_000);
        ExperimentRun p3Assisted = completedRun(3, ExperimentCondition.ASSISTED, "texto", 60_000);
        ExperimentRun p3Unassisted = completedRun(3, ExperimentCondition.UNASSISTED, "texto excluido", 60_000);
        p3Unassisted.exclude("Excluded for a documented reason", researcher, NOW);
        ExperimentRun p4Pending = pendingRun(4, ExperimentCondition.UNASSISTED);
        stubOwnedStudy();
        when(participantRepository.findByStudyIdOrderByPseudonymAsc(studyId)).thenReturn(participants);
        when(runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId))
                .thenReturn(List.of(p1Assisted, p1Unassisted, p2Assisted, p3Assisted, p3Unassisted, p4Pending));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of());
        lenient().when(sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(anyCollection())).thenReturn(List.of());

        StudyResultsResponse results = service.results(researcherId, studyId);

        assertThat(results.studyId()).isEqualTo(studyId);
        assertThat(results.sample().participantsTotal()).isEqualTo(4);
        assertThat(results.sample().participantsIncluded()).isEqualTo(1);
        assertThat(results.sample().participantsWithIncompletePair()).isEqualTo(2);
        assertThat(results.sample().runsCompleted()).isEqualTo(5);
        assertThat(results.sample().runsIncluded()).isEqualTo(2);
        assertThat(results.sample().runsExcluded()).isEqualTo(1);
        assertThat(results.sample().runsInIncompletePairs()).isEqualTo(2);

        // No annotation batch: PEO and TAS stay null and the status says why; PPM needs no annotation.
        assertThat(results.peo()).isNull();
        assertThat(results.orthographyAnnotation().status()).isEqualTo("NO_BATCH");
        assertThat(results.tas()).isNull();
        assertThat(results.tasAccepted()).isNull();
        assertThat(results.semanticAnnotation().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(results.ppm().paired().n()).isEqualTo(1);
        assertThat(results.ppm().paired().assistedMean()).isEqualTo(2.0);
        assertThat(results.ppm().paired().unassistedMean()).isEqualTo(5.0);
        assertThat(results.ppm().paired().meanDelta()).isEqualTo(-3.0);
        assertThat(results.ppm().paired().ci95Lower()).isNull();
        assertThat(results.ppm().descriptive()).isTrue();
        assertThat(results.ppm().nonInferiorityMargin()).isNull();
        assertThat(results.ppm().nonInferior()).isNull();

        assertThat(results.participants()).extracting(StudyResultsResponse.ParticipantResult::pseudonym)
                .containsExactly("P-001");
        assertThat(results.provenance().protocolVersions()).containsExactly(1);
        assertThat(results.provenance().datasets()).isEmpty();
        assertThat(results.provenance().computedAt()).isEqualTo(NOW);
        assertThat(results.toString()).doesNotContain("student-real-name", "Colegio", "@");
    }

    @Test
    void unownedStudyIsNotFound() {
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.results(researcherId, studyId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Study not found");
    }

    // --------------------------------------------------------------------- PEO

    @Test
    void peoRequiresACurrentAdjudicationCoveringEveryIncludedRun() {
        ExperimentRun p1Assisted = completedRun(1, ExperimentCondition.ASSISTED, "uno dos tres cuatro", 60_000);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, "a b c d e", 60_000);
        ExperimentRun p2Assisted = completedRun(2, ExperimentCondition.ASSISTED, "uno dos", 60_000);
        ExperimentRun p2Unassisted = completedRun(2, ExperimentCondition.UNASSISTED, "uno dos", 60_000);
        stubStudyRuns(p1Assisted, p1Unassisted, p2Assisted, p2Unassisted);

        // Batch with two raters but no adjudication.
        AnnotationBatch ratersOnly = batch(AnnotationKind.ORTHOGRAPHY, NOW.minusSeconds(300));
        List<AnnotationItem> ratersOnlyItems = List.of(
                orthographyItem(ratersOnly, 0, p1Assisted, null), orthographyItem(ratersOnly, 1, p1Unassisted, null),
                orthographyItem(ratersOnly, 2, p2Assisted, null), orthographyItem(ratersOnly, 3, p2Unassisted, null));
        stubBatch(ratersOnly, ratersOnlyItems, raterImports(ratersOnly));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(ratersOnly));

        StudyResultsResponse notAdjudicated = service.results(researcherId, studyId);
        assertThat(notAdjudicated.peo()).isNull();
        assertThat(notAdjudicated.orthographyAnnotation().status()).isEqualTo("NOT_ADJUDICATED");
        assertThat(notAdjudicated.orthographyAnnotation().batchId()).isNull();
        assertThat(notAdjudicated.provenance().datasets()).isEmpty();

        // An older adjudicated batch that misses P-002's runs (created before they completed).
        AnnotationBatch partial = batch(AnnotationKind.ORTHOGRAPHY, NOW.minusSeconds(600));
        List<AnnotationItem> partialItems = List.of(
                orthographyItem(partial, 0, p1Assisted, 1), orthographyItem(partial, 1, p1Unassisted, 2));
        stubBatch(partial, partialItems, adjudicatedImports(partial));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(ratersOnly, partial));

        StudyResultsResponse incomplete = service.results(researcherId, studyId);
        assertThat(incomplete.peo()).isNull();
        assertThat(incomplete.orthographyAnnotation().status()).isEqualTo("INCOMPLETE_COVERAGE");
        assertThat(incomplete.orthographyAnnotation().batchId()).isEqualTo(partial.getId());
        assertThat(incomplete.orthographyAnnotation().message()).contains("2 of 4");
        assertThat(incomplete.participants()).allSatisfy(row -> assertThat(row.peoAssisted()).isNull());

        // A newer batch adjudicated over every included run: PEO is computed from it, not from the raters.
        AnnotationBatch complete = batch(AnnotationKind.ORTHOGRAPHY, NOW.minusSeconds(60));
        List<AnnotationItem> completeItems = List.of(
                orthographyItem(complete, 0, p1Assisted, 1), orthographyItem(complete, 1, p1Unassisted, 2),
                orthographyItem(complete, 2, p2Assisted, 0), orthographyItem(complete, 3, p2Unassisted, 0));
        completeItems.forEach(item -> {
            item.record(AnnotationSlot.RATER_1, 2);
            item.record(AnnotationSlot.RATER_2, 2);
        });
        List<AnnotationImport> completeImports = adjudicatedImports(complete);
        stubBatch(complete, completeItems, completeImports);
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(complete, ratersOnly, partial));

        StudyResultsResponse results = service.results(researcherId, studyId);
        // P-001: assisted 1/4 = 25, unassisted 2/5 = 40, delta -15, relative reduction 37.5
        // P-002: assisted 0/2 = 0, unassisted 0/2 = 0, delta 0, relative reduction not computed (unassisted = 0)
        assertThat(results.orthographyAnnotation().status()).isEqualTo("ADJUDICATED");
        assertThat(results.orthographyAnnotation().batchId()).isEqualTo(complete.getId());
        assertThat(results.peo().paired().n()).isEqualTo(2);
        assertThat(results.peo().paired().assistedMean()).isEqualTo(12.5);
        assertThat(results.peo().paired().unassistedMean()).isEqualTo(20.0);
        assertThat(results.peo().paired().meanDelta()).isEqualTo(-7.5);
        assertThat(results.peo().relativeReductionMean()).isEqualTo(37.5);
        assertThat(results.peo().relativeReductionN()).isEqualTo(1);
        assertThat(results.peo().relativeReductionSkipped()).isEqualTo(1);
        assertThat(results.participants()).extracting(
                StudyResultsResponse.ParticipantResult::pseudonym,
                StudyResultsResponse.ParticipantResult::peoAssisted,
                StudyResultsResponse.ParticipantResult::peoUnassisted,
                StudyResultsResponse.ParticipantResult::peoDelta,
                StudyResultsResponse.ParticipantResult::peoRelativeReduction)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("P-001", 25.0, 40.0, -15.0, 37.5),
                        org.assertj.core.groups.Tuple.tuple("P-002", 0.0, 0.0, 0.0, null));
        StudyResultsResponse.Dataset dataset = results.provenance().datasets().get(0);
        assertThat(results.provenance().datasets()).hasSize(1);
        assertThat(dataset.kind()).isEqualTo(AnnotationKind.ORTHOGRAPHY);
        assertThat(dataset.batchId()).isEqualTo(complete.getId());
        assertThat(dataset.exportSha256()).isEqualTo(complete.getExportSha256());
        assertThat(dataset.adjudicationImportId()).isEqualTo(completeImports.get(2).getId());
        assertThat(dataset.adjudicationSha256()).isEqualTo(completeImports.get(2).getFileSha256());
    }

    @Test
    void peoAveragesSeveralRunsPerConditionBeforePairing() {
        ExperimentRun p1AssistedA = completedRun(1, ExperimentCondition.ASSISTED, words(10), 60_000);
        ExperimentRun p1AssistedB = completedRun(1, ExperimentCondition.ASSISTED, words(10), 120_000);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, words(10), 60_000);
        stubStudyRuns(p1AssistedA, p1AssistedB, p1Unassisted);
        AnnotationBatch batch = batch(AnnotationKind.ORTHOGRAPHY, NOW.minusSeconds(60));
        stubBatch(batch, List.of(
                orthographyItem(batch, 0, p1AssistedA, 1),
                orthographyItem(batch, 1, p1AssistedB, 3),
                orthographyItem(batch, 2, p1Unassisted, 5)), adjudicatedImports(batch));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(batch));

        StudyResultsResponse results = service.results(researcherId, studyId);

        // assisted runs: 10 and 30 -> 20; unassisted 50 -> delta -30, relative reduction 60
        StudyResultsResponse.ParticipantResult row = results.participants().get(0);
        assertThat(row.peoAssisted()).isEqualTo(20.0);
        assertThat(row.peoUnassisted()).isEqualTo(50.0);
        assertThat(row.peoDelta()).isEqualTo(-30.0);
        assertThat(row.peoRelativeReduction()).isEqualTo(60.0);
        // ppm: assisted 10 and 5 -> 7.5; unassisted 10 -> delta -2.5
        assertThat(row.ppmAssisted()).isEqualTo(7.5);
        assertThat(row.ppmUnassisted()).isEqualTo(10.0);
        assertThat(row.ppmDelta()).isEqualTo(-2.5);
        assertThat(results.sample().runsIncluded()).isEqualTo(3);
        assertThat(results.peo().paired().n()).isEqualTo(1);
        assertThat(results.peo().upperCiBelowZero()).isNull();
    }

    @Test
    void ppmNonInferiorityIsJudgedOnlyWithAConfiguredMargin() {
        service = newService(new ResearchProperties(Duration.ofMinutes(30), 2.0, null));
        // ppm deltas: P1 -1, P2 -1, P3 -2, P4 0 -> mean -1, sd 0.8165, se 0.40825, t(0.975,3) 3.1824 -> CI [-2.299, 0.299]
        List<ExperimentRun> runs = List.of(
                completedRun(1, ExperimentCondition.ASSISTED, words(9), 60_000),
                completedRun(1, ExperimentCondition.UNASSISTED, words(10), 60_000),
                completedRun(2, ExperimentCondition.ASSISTED, words(9), 60_000),
                completedRun(2, ExperimentCondition.UNASSISTED, words(10), 60_000),
                completedRun(3, ExperimentCondition.ASSISTED, words(8), 60_000),
                completedRun(3, ExperimentCondition.UNASSISTED, words(10), 60_000),
                completedRun(4, ExperimentCondition.ASSISTED, words(10), 60_000),
                completedRun(4, ExperimentCondition.UNASSISTED, words(10), 60_000));
        stubStudyRuns(runs.toArray(ExperimentRun[]::new));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of());

        StudyResultsResponse results = service.results(researcherId, studyId);

        assertThat(results.ppm().descriptive()).isFalse();
        assertThat(results.ppm().nonInferiorityMargin()).isEqualTo(2.0);
        assertThat(results.ppm().paired().meanDelta()).isCloseTo(-1.0, within(EPS));
        assertThat(results.ppm().paired().ci95Lower()).isCloseTo(-2.299228, within(1e-5));
        assertThat(results.ppm().nonInferior()).isFalse();

        service = newService(new ResearchProperties(Duration.ofMinutes(30), 2.5, null));
        assertThat(service.results(researcherId, studyId).ppm().nonInferior()).isTrue();
    }

    // --------------------------------------------------------------------- TAS

    @Test
    void tasUsesAdjudicatedSemanticScoresAndTheAcceptedSuggestion() {
        ExperimentRun p1Assisted = completedRun(1, ExperimentCondition.ASSISTED, "final uno", 60_000);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, "final uno sin", 60_000);
        ExperimentRun p2Assisted = completedRun(2, ExperimentCondition.ASSISTED, "final dos", 60_000);
        ExperimentRun p2Unassisted = completedRun(2, ExperimentCondition.UNASSISTED, "final dos sin", 60_000);
        stubStudyRuns(p1Assisted, p1Unassisted, p2Assisted, p2Unassisted);
        // P-001: accepted the second alternative (harmful), rejected one (safe), accepted the first (minor)
        CorrectionSession acceptedHarmful = session(p1Assisted, "ola", "hola", List.of("Hola", "ola!"));
        acceptedHarmful.registerFeedback("Hola", null, true, 1, null);
        CorrectionSession rejectedSafe = session(p1Assisted, "ke", "que", List.of());
        rejectedSafe.registerFeedback(null, null, false, 0, "UNDO");
        CorrectionSession acceptedMinor = session(p1Assisted, "asta", "hasta", List.of());
        acceptedMinor.registerFeedback("hasta", null, true, 1, null);
        // P-002: one unanswered suggestion, harmful; a session without suggestions is not evaluated
        CorrectionSession unansweredHarmful = session(p2Assisted, "bien", "bienes", List.of());
        CorrectionSession nothingOffered = session(p2Assisted, "ok", "", List.of());
        when(sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(acceptedHarmful, rejectedSafe, acceptedMinor, unansweredHarmful, nothingOffered));

        AnnotationBatch batch = batch(AnnotationKind.SEMANTIC, NOW.minusSeconds(60));
        List<AnnotationItem> items = List.of(
                semanticItem(batch, 0, p1Assisted, acceptedHarmful, 1, 0),
                semanticItem(batch, 1, p1Assisted, rejectedSafe, 0, 2),
                semanticItem(batch, 2, p1Assisted, acceptedMinor, 0, 1),
                semanticItem(batch, 3, p2Assisted, unansweredHarmful, 0, 0));
        stubBatch(batch, items, adjudicatedImports(batch));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(batch));

        StudyResultsResponse results = service.results(researcherId, studyId);

        // TAS per run: P-001 1/3 = 33.333, P-002 1/1 = 100 -> participant mean 66.667; pooled 2/4 = 50
        assertThat(results.semanticAnnotation().status()).isEqualTo("ADJUDICATED");
        StudyResultsResponse.TasResult tas = results.tas();
        assertThat(tas.participantsEvaluated()).isEqualTo(2);
        assertThat(tas.participantsWithoutDenominator()).isZero();
        assertThat(tas.suggestionsEvaluated()).isEqualTo(4);
        assertThat(tas.harmfulSuggestions()).isEqualTo(2);
        assertThat(tas.pooledRate()).isEqualTo(50.0);
        assertThat(tas.participantMean()).isCloseTo(66.666667, within(EPS));
        assertThat(tas.ci95Upper()).isNotNull();
        assertThat(tas.descriptive()).isTrue();
        assertThat(tas.limit()).isNull();
        assertThat(tas.upperCiBelowLimit()).isNull();

        // TAS accepted: P-001 accepted 2 (1 harmful) -> 50; P-002 accepted none -> excluded individually; pooled 1/2
        StudyResultsResponse.TasResult accepted = results.tasAccepted();
        assertThat(accepted.participantsEvaluated()).isEqualTo(1);
        assertThat(accepted.participantsWithoutDenominator()).isEqualTo(1);
        assertThat(accepted.suggestionsEvaluated()).isEqualTo(2);
        assertThat(accepted.harmfulSuggestions()).isEqualTo(1);
        assertThat(accepted.pooledRate()).isEqualTo(50.0);
        assertThat(accepted.participantMean()).isEqualTo(50.0);
        assertThat(accepted.ci95Lower()).isNull();

        assertThat(results.participants()).extracting(
                StudyResultsResponse.ParticipantResult::pseudonym,
                StudyResultsResponse.ParticipantResult::tas,
                StudyResultsResponse.ParticipantResult::tasAccepted)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("P-001", 100.0 / 3, 50.0),
                        org.assertj.core.groups.Tuple.tuple("P-002", 100.0, null));
        assertThat(results.provenance().datasets()).extracting(StudyResultsResponse.Dataset::kind)
                .containsExactly(AnnotationKind.SEMANTIC);

        // With a configured limit the criterion is judged on the upper CI bound, never on the mean.
        service = newService(new ResearchProperties(Duration.ofMinutes(30), null, 90.0));
        StudyResultsResponse limited = service.results(researcherId, studyId);
        assertThat(limited.tas().descriptive()).isFalse();
        assertThat(limited.tas().limit()).isEqualTo(90.0);
        assertThat(limited.tas().upperCiBelowLimit()).isFalse();
        assertThat(limited.tasAccepted().upperCiBelowLimit()).isNull();
    }

    @Test
    void tasIsNullWhenAnIncludedSuggestionLacksAnAdjudicatedScore() {
        ExperimentRun p1Assisted = completedRun(1, ExperimentCondition.ASSISTED, "final uno", 60_000);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, "final uno sin", 60_000);
        stubStudyRuns(p1Assisted, p1Unassisted);
        CorrectionSession evaluated = session(p1Assisted, "ola", "hola", List.of());
        CorrectionSession later = session(p1Assisted, "ke", "que", List.of());
        when(sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(evaluated, later));
        AnnotationBatch batch = batch(AnnotationKind.SEMANTIC, NOW.minusSeconds(60));
        stubBatch(batch, List.of(semanticItem(batch, 0, p1Assisted, evaluated, 0, 0)), adjudicatedImports(batch));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(batch));

        StudyResultsResponse results = service.results(researcherId, studyId);

        assertThat(results.tas()).isNull();
        assertThat(results.tasAccepted()).isNull();
        assertThat(results.semanticAnnotation().status()).isEqualTo("INCOMPLETE_COVERAGE");
        assertThat(results.semanticAnnotation().message()).contains("1 of 2");
        assertThat(results.participants().get(0).tas()).isNull();
    }

    // --------------------------------------------------------------------- CSV

    @Test
    void analysisCsvListsRunsAndEvaluatedSuggestionsWithPseudonymsOnly() {
        ExperimentRun p1Assisted = completedRun(1, ExperimentCondition.ASSISTED, "=uno dos", 120_000);
        ExperimentRun p1Unassisted = completedRun(1, ExperimentCondition.UNASSISTED, "tres, \"cuatro\"", 60_000);
        ExperimentRun p2Assisted = completedRun(2, ExperimentCondition.ASSISTED, "incompleto", 60_000);
        stubStudyRuns(p1Assisted, p1Unassisted, p2Assisted);
        stubResearcher();
        CorrectionSession accepted = session(p1Assisted, "ola", "hola", List.of("Hola"));
        accepted.registerFeedback("Hola", null, true, 1, null);
        CorrectionSession rejected = session(p1Assisted, "ke", "que", List.of());
        rejected.registerFeedback(null, null, false, 0, "UNDO");
        when(sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(accepted, rejected));
        AnnotationBatch orthography = batch(AnnotationKind.ORTHOGRAPHY, NOW.minusSeconds(120));
        stubBatch(orthography, List.of(
                orthographyItem(orthography, 0, p1Assisted, 1), orthographyItem(orthography, 1, p1Unassisted, 0)),
                adjudicatedImports(orthography));
        AnnotationBatch semantic = batch(AnnotationKind.SEMANTIC, NOW.minusSeconds(60));
        stubBatch(semantic, List.of(
                semanticItem(semantic, 0, p1Assisted, accepted, 1, 0),
                semanticItem(semantic, 1, p1Assisted, rejected, 0, 2)), adjudicatedImports(semantic));
        when(batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId)).thenReturn(List.of(semantic, orthography));

        AnnotationCsvFile file = service.analysisCsv(researcherId, studyId);
        String csv = new String(file.bytes(), UTF_8);

        assertThat(file.filename()).endsWith(".csv");
        assertThat(csv).startsWith("pseudonym,condition,task,protocol_version,included,excluded,run_id,duration_ms,"
                + "word_count,orthography_errors,final_text,suggestion_index,original_text,suggestion,semantic_score,accepted\n");
        assertThat(csv).contains("P-001,ASSISTED,TASK_A,1,true,false," + p1Assisted.getId() + ",120000,2,1, =uno dos,1,ola,Hola,0,true\n")
                .contains("P-001,ASSISTED,TASK_A,1,true,false," + p1Assisted.getId() + ",120000,2,1, =uno dos,0,ke,que,2,false\n")
                .contains("P-001,UNASSISTED,TASK_A,1,true,false," + p1Unassisted.getId() + ",60000,2,0,\"tres, \"\"cuatro\"\"\",,,,,\n")
                .contains("P-002,ASSISTED,TASK_A,1,false,false," + p2Assisted.getId() + ",60000,1,,incompleto,,,,,\n");
        assertThat(csv).doesNotContain("student-real-name", "Colegio", "T-");
        assertThat(file.sha256()).hasSize(64);

        ArgumentCaptor<ResearchAuditEvent> events = ArgumentCaptor.forClass(ResearchAuditEvent.class);
        verify(auditRepository).save(events.capture());
        assertThat(events.getValue().getAction()).isEqualTo("STUDY_RESULTS_EXPORTED");
        assertThat(String.valueOf(events.getValue().getDetail())).doesNotContain("uno dos", "P-001", "ola");
    }

    // ----------------------------------------------------------------- helpers

    private void stubOwnedStudy() {
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
    }

    private void stubResearcher() {
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
    }

    private void stubStudyRuns(ExperimentRun... runs) {
        stubOwnedStudy();
        when(participantRepository.findByStudyIdOrderByPseudonymAsc(studyId)).thenReturn(participants);
        when(runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId)).thenReturn(List.of(runs));
        lenient().when(sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(anyCollection())).thenReturn(List.of());
    }

    private void stubBatch(AnnotationBatch batch, List<AnnotationItem> items, List<AnnotationImport> imports) {
        lenient().when(itemRepository.findByBatchIdOrderByPositionAsc(batch.getId())).thenReturn(items);
        lenient().when(importRepository.findByBatchIdOrderByVersionAsc(batch.getId())).thenReturn(imports);
    }

    private StudyParticipant participant(int number) {
        return participants.stream()
                .filter(p -> p.getPseudonym().equals(StudyParticipant.pseudonymFor(number)))
                .findFirst()
                .orElseGet(() -> {
                    StudyParticipant created = new StudyParticipant(study, number);
                    participants.add(created);
                    return created;
                });
    }

    private ExperimentRun pendingRun(int participantNumber, ExperimentCondition condition) {
        return new ExperimentRun(participant(participantNumber), protocol,
                protocol.findTask(TaskVariant.TASK_A).orElseThrow(), condition, "h".repeat(64),
                NOW.plus(Duration.ofMinutes(30)), NOW);
    }

    private ExperimentRun completedRun(int participantNumber, ExperimentCondition condition, String finalText, long durationMs) {
        ExperimentRun run = pendingRun(participantNumber, condition);
        run.redeem(NOW);
        run.start(NOW);
        run.complete(finalText, durationMs, UUID.randomUUID(), NOW.plusSeconds(60));
        run.recordAppVersion("app-1.0");
        return run;
    }

    private static String words(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(i == 0 ? "" : " ").append("w").append(i);
        }
        return text.toString();
    }

    private CorrectionSession session(ExperimentRun run, String original, String corrected, List<String> alternatives) {
        CorrectionSession session = new CorrectionSession(student, original, run);
        String json = "[" + String.join(",", alternatives.stream().map(s -> "\"" + s + "\"").toList()) + "]";
        session.complete(corrected, 0, json, 10L);
        return session;
    }

    private AnnotationBatch batch(AnnotationKind kind, Instant createdAt) {
        AnnotationBatch batch = new AnnotationBatch(study, kind, researcher, createdAt);
        batch.freeze(1, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        return batch;
    }

    private static AnnotationItem orthographyItem(AnnotationBatch batch, int position, ExperimentRun run, Integer adjudicated) {
        AnnotationItem item = new AnnotationItem(batch, "T-" + position + batch.getId().toString().substring(0, 6), position, run, null, null);
        if (adjudicated != null) {
            item.record(AnnotationSlot.ADJUDICATED, adjudicated);
        }
        return item;
    }

    private static AnnotationItem semanticItem(
            AnnotationBatch batch, int position, ExperimentRun run, CorrectionSession session, int index, int adjudicated) {
        AnnotationItem item = new AnnotationItem(batch, "T-" + position + batch.getId().toString().substring(0, 6), position, run, session, index);
        item.record(AnnotationSlot.ADJUDICATED, adjudicated);
        return item;
    }

    private List<AnnotationImport> raterImports(AnnotationBatch batch) {
        return List.of(
                new AnnotationImport(batch, AnnotationSlot.RATER_1, 1, "Ana", HASH, "sample_code,score\n", researcher, NOW),
                new AnnotationImport(batch, AnnotationSlot.RATER_2, 1, "Beto", HASH, "sample_code,score\n", researcher, NOW));
    }

    private List<AnnotationImport> adjudicatedImports(AnnotationBatch batch) {
        List<AnnotationImport> raters = raterImports(batch);
        String hash = "b".repeat(63) + batch.getId().toString().charAt(0);
        AnnotationImport adjudicated = AnnotationImport.adjudicated(batch, 1, "Consenso", hash,
                "sample_code,score\n", researcher, NOW, raters.get(0), raters.get(1));
        return List.of(raters.get(0), raters.get(1), adjudicated);
    }
}
