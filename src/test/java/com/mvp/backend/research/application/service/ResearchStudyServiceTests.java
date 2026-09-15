package com.mvp.backend.research.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.mvp.backend.config.ResearchProperties;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AccessCodeResponse;
import com.mvp.backend.research.application.dto.CreateProtocolRequest;
import com.mvp.backend.research.application.dto.CreateStudyRequest;
import com.mvp.backend.research.application.dto.ExperimentRunResponse;
import com.mvp.backend.research.application.dto.ParticipantResponse;
import com.mvp.backend.research.application.dto.RunReasonRequest;
import com.mvp.backend.research.application.dto.StudyProtocolResponse;
import com.mvp.backend.research.domain.model.ParticipantSequence;
import com.mvp.backend.research.domain.model.ProtocolStatus;
import com.mvp.backend.research.domain.model.ProtocolTask;
import com.mvp.backend.research.domain.model.ResearchAuditEvent;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.StudyStatus;
import com.mvp.backend.research.domain.model.TaskVariant;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.research.domain.repository.StudyProtocolRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;

@ExtendWith(MockitoExtension.class)
class ResearchStudyServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private ResearchStudyRepository studyRepository;

    @Mock
    private StudyProtocolRepository protocolRepository;

    @Mock
    private StudyParticipantRepository participantRepository;

    @Mock
    private ExperimentRunRepository runRepository;

    @Mock
    private ResearchAuditEventRepository auditRepository;

    @Mock
    private ResearcherRepository researcherRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ResearchStudyService service;

    private Researcher researcher;
    private UUID researcherId;
    private ResearchStudy study;
    private UUID studyId;

    @BeforeEach
    void setUp() {
        service = new ResearchStudyService(
                studyRepository,
                protocolRepository,
                participantRepository,
                runRepository,
                auditRepository,
                researcherRepository,
                transactionManager,
                new ResearchProperties(TTL, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
        researcher = new Researcher("lab@example.edu", "hash");
        researcherId = researcher.getId();
        study = new ResearchStudy("EXP-01", "Teclado predictivo", researcher);
        study.activate();
        studyId = study.getId();
    }

    // ---------------------------------------------------------------- participants

    @Test
    void createsAlternatingPseudonymsAndSequences() {
        when(studyRepository.findOwnedForUpdate(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(participantRepository.save(any(StudyParticipant.class))).thenAnswer(inv -> inv.getArgument(0));

        ParticipantResponse first = service.createParticipant(researcherId, studyId);
        ParticipantResponse second = service.createParticipant(researcherId, studyId);

        assertThat(first.pseudonym()).isEqualTo("P-001");
        assertThat(first.sequence()).isEqualTo(ParticipantSequence.ASSISTED_FIRST);
        assertThat(second.pseudonym()).isEqualTo("P-002");
        assertThat(second.sequence()).isEqualTo(ParticipantSequence.UNASSISTED_FIRST);
        assertThat(first.completedRuns()).isZero();
        assertThat(first.nextSession().task()).isEqualTo(TaskVariant.TASK_A);
        assertThat(first.nextSession().condition()).isEqualTo(ExperimentCondition.ASSISTED);
        assertThat(second.nextSession().condition()).isEqualTo(ExperimentCondition.UNASSISTED);
        assertThat(study.getNextParticipantNumber()).isEqualTo(3);
        verify(studyRepository, times(2)).save(study);
        verify(auditRepository, times(2)).save(any(ResearchAuditEvent.class));
    }

    @Test
    void unknownOrUnownedStudyIsNotFoundForParticipants() {
        when(studyRepository.findOwnedForUpdate(studyId, researcherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createParticipant(researcherId, studyId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Study not found");
        verify(participantRepository, never()).save(any());
    }

    @Test
    void participantsRequireAnActiveStudy() {
        ResearchStudy draft = new ResearchStudy("EXP-02", "Borrador", researcher);
        when(studyRepository.findOwnedForUpdate(draft.getId(), researcherId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.createParticipant(researcherId, draft.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Study is not active");
    }

    @Test
    void expiredPendingCodeIsNotReportedAsOpen() {
        StudyParticipant expired = new StudyParticipant(study, 1);
        StudyParticipant redeemed = new StudyParticipant(study, 2);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        ProtocolTask taskA = protocol.findTask(TaskVariant.TASK_A).orElseThrow();
        ExperimentRun staleNeverRedeemed = new ExperimentRun(expired, protocol, taskA,
                expired.nextCondition(0), "a".repeat(64), NOW.minusSeconds(1), NOW.minus(TTL));
        ExperimentRun staleButRedeemed = new ExperimentRun(redeemed, protocol, taskA,
                redeemed.nextCondition(0), "c".repeat(64), NOW.minusSeconds(1), NOW.minus(TTL));
        staleButRedeemed.redeem(NOW.minus(TTL));
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(participantRepository.findByStudyIdOrderByPseudonymAsc(studyId)).thenReturn(List.of(expired, redeemed));
        when(runRepository.findByParticipantIdOrderByCreatedAtAsc(expired.getId())).thenReturn(List.of(staleNeverRedeemed));
        when(runRepository.findByParticipantIdOrderByCreatedAtAsc(redeemed.getId())).thenReturn(List.of(staleButRedeemed));

        List<ParticipantResponse> participants = service.listParticipants(researcherId, studyId);

        assertThat(participants).extracting(ParticipantResponse::pseudonym).containsExactly("P-001", "P-002");
        assertThat(participants.get(0).hasOpenRun()).as("expired, never redeemed").isFalse();
        assertThat(participants.get(1).hasOpenRun()).as("expired but redeemed").isTrue();
        assertThat(participants.get(0).completedRuns()).isZero();
        assertThat(participants.get(0).nextSession().task()).isEqualTo(TaskVariant.TASK_A);
    }

    // ---------------------------------------------------------------- studies

    @Test
    void duplicateStudyCodeRaceIsAConflict() {
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(studyRepository.existsByCode("EXP-09")).thenReturn(false);
        when(studyRepository.saveAndFlush(any(ResearchStudy.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("duplicate key"),
                        "uk_study_code")));

        assertThatThrownBy(() -> service.createStudy(researcherId, new CreateStudyRequest("EXP-09", "Duplicado")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Study code already exists");
        verify(auditRepository, never()).save(any());
    }

    @Test
    void unrecognisedIntegrityViolationOnStudyCreationIsRethrown() {
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(studyRepository.existsByCode("EXP-10")).thenReturn(false);
        DataIntegrityViolationException other = new DataIntegrityViolationException("null value in column title");
        when(studyRepository.saveAndFlush(any(ResearchStudy.class))).thenThrow(other);

        assertThatThrownBy(() -> service.createStudy(researcherId, new CreateStudyRequest("EXP-10", "Otro")))
                .isSameAs(other);
    }

    // ---------------------------------------------------------------- protocols

    @Test
    void protocolCreationInsertsBothTasksAsNextVersion() {
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(protocolRepository.findFirstByStudyIdOrderByVersionDesc(studyId))
                .thenReturn(Optional.of(new StudyProtocol(study, 2)));
        when(protocolRepository.save(any(StudyProtocol.class))).thenAnswer(inv -> inv.getArgument(0));

        StudyProtocolResponse response = service.createProtocol(
                researcherId, studyId, new CreateProtocolRequest("Escribe sobre tu mascota", "Escribe sobre tu escuela"));

        assertThat(response.version()).isEqualTo(3);
        assertThat(response.status()).isEqualTo(ProtocolStatus.DRAFT);
        assertThat(response.taskAPrompt()).isEqualTo("Escribe sobre tu mascota");
        assertThat(response.taskBPrompt()).isEqualTo("Escribe sobre tu escuela");
        verify(auditRepository).save(any(ResearchAuditEvent.class));
    }

    @Test
    void activatingProtocolRetiresPreviousVersionAndActivatesDraftStudy() {
        ResearchStudy draftStudy = new ResearchStudy("EXP-03", "Nuevo", researcher);
        StudyProtocol previous = protocolWithTasks(draftStudy, 1);
        previous.activate();
        StudyProtocol next = protocolWithTasks(draftStudy, 2);

        when(studyRepository.findOwnedForUpdate(draftStudy.getId(), researcherId)).thenReturn(Optional.of(draftStudy));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(protocolRepository.findByIdAndStudyId(next.getId(), draftStudy.getId())).thenReturn(Optional.of(next));
        when(protocolRepository.findFirstByStudyIdAndStatus(draftStudy.getId(), ProtocolStatus.ACTIVE))
                .thenReturn(Optional.of(previous));
        // El retiro se vacia a la BD antes de activar la nueva version: nunca hay dos ACTIVE a la vez.
        when(protocolRepository.saveAndFlush(previous)).thenAnswer(inv -> {
            assertThat(previous.getStatus()).isEqualTo(ProtocolStatus.RETIRED);
            assertThat(next.getStatus()).isEqualTo(ProtocolStatus.DRAFT);
            return previous;
        });

        StudyProtocolResponse response = service.activateProtocol(researcherId, draftStudy.getId(), next.getId());

        assertThat(response.status()).isEqualTo(ProtocolStatus.ACTIVE);
        assertThat(previous.getStatus()).isEqualTo(ProtocolStatus.RETIRED);
        assertThat(next.getStatus()).isEqualTo(ProtocolStatus.ACTIVE);
        assertThat(draftStudy.getStatus()).isEqualTo(StudyStatus.ACTIVE);
        verify(protocolRepository).saveAndFlush(previous);
    }

    @Test
    void activateProtocolLocksTheStudy() {
        StudyProtocol protocol = protocolWithTasks(study, 1);
        when(studyRepository.findOwnedForUpdate(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(protocolRepository.findByIdAndStudyId(protocol.getId(), studyId)).thenReturn(Optional.of(protocol));
        when(protocolRepository.findFirstByStudyIdAndStatus(studyId, ProtocolStatus.ACTIVE)).thenReturn(Optional.empty());

        service.activateProtocol(researcherId, studyId, protocol.getId());

        verify(studyRepository).findOwnedForUpdate(studyId, researcherId);
        verify(studyRepository, never()).findByIdAndCreatedById(any(), any());
        assertThat(protocol.getStatus()).isEqualTo(ProtocolStatus.ACTIVE);
    }

    @Test
    void closedStudyRejectsProtocolActivation() {
        study.close();
        StudyProtocol protocol = protocolWithTasks(study, 1);
        when(studyRepository.findOwnedForUpdate(studyId, researcherId)).thenReturn(Optional.of(study));
        when(protocolRepository.findByIdAndStudyId(protocol.getId(), studyId)).thenReturn(Optional.of(protocol));

        assertThatThrownBy(() -> service.activateProtocol(researcherId, studyId, protocol.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Study is closed");
        assertThat(protocol.getStatus()).isEqualTo(ProtocolStatus.DRAFT);
    }

    // ---------------------------------------------------------------- access codes

    @Test
    void generatesOneTimeCodeAndStoresOnlyItsHash() throws Exception {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        stubAuditResearcher();
        when(runRepository.saveAndFlush(any(ExperimentRun.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessCodeResponse response = service.generateAccessCode(researcherId, studyId, participant.getId());

        assertThat(response.code()).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(response.pseudonym()).isEqualTo("P-001");
        assertThat(response.task()).isEqualTo(TaskVariant.TASK_A);
        assertThat(response.condition()).isEqualTo(ExperimentCondition.ASSISTED);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(TTL));

        ArgumentCaptor<ExperimentRun> runCaptor = ArgumentCaptor.forClass(ExperimentRun.class);
        verify(runRepository).saveAndFlush(runCaptor.capture());
        ExperimentRun run = runCaptor.getValue();
        assertThat(run.getAccessCodeHash()).isEqualTo(sha256Hex(response.code()));
        assertThat(run.getAccessCodeHash()).doesNotContain(response.code());
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.PENDING);
        assertThat(run.getCondition()).isEqualTo(ExperimentCondition.ASSISTED);
        assertThat(run.getTask().getVariant()).isEqualTo(TaskVariant.TASK_A);
        assertThat(run.getAccessCodeExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(response.runId()).isEqualTo(run.getId());

        ArgumentCaptor<ResearchAuditEvent> auditCaptor = ArgumentCaptor.forClass(ResearchAuditEvent.class);
        verify(auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("ACCESS_CODE_GENERATED");
        assertThat(auditCaptor.getValue().getDetail()).doesNotContain(response.code());
    }

    @Test
    void secondSessionSwitchesTaskAndCondition() {
        StudyParticipant participant = new StudyParticipant(study, 2); // UNASSISTED_FIRST
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 1);
        stubAuditResearcher();
        when(runRepository.saveAndFlush(any(ExperimentRun.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessCodeResponse response = service.generateAccessCode(researcherId, studyId, participant.getId());

        assertThat(response.task()).isEqualTo(TaskVariant.TASK_B);
        assertThat(response.condition()).isEqualTo(ExperimentCondition.ASSISTED);
    }

    @Test
    void thirdSessionIsRejected() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 2);

        assertThatThrownBy(() -> service.generateAccessCode(researcherId, studyId, participant.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Participant already completed both conditions");
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void openRunBlocksANewCode() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(participantRepository.findByIdAndStudyId(participant.getId(), studyId)).thenReturn(Optional.of(participant));
        when(protocolRepository.findFirstByStudyIdAndStatus(studyId, ProtocolStatus.ACTIVE)).thenReturn(Optional.of(protocol));
        when(runRepository.findByParticipantIdOrderByCreatedAtAsc(participant.getId())).thenReturn(List.of());
        when(runRepository.existsByParticipantIdAndStatusIn(eq(participant.getId()), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.generateAccessCode(researcherId, studyId, participant.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Participant already has an open run");
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void stalePendingCodeIsExpiredBeforeIssuingANewOne() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        ExperimentRun stale = new ExperimentRun(participant, protocol, protocol.findTask(TaskVariant.TASK_A).orElseThrow(),
                ExperimentCondition.ASSISTED, "a".repeat(64), NOW.minusSeconds(1), NOW.minus(TTL));
        stubCodeGeneration(participant, protocol, 0);
        stubAuditResearcher();
        when(runRepository.findByParticipantIdOrderByCreatedAtAsc(participant.getId())).thenReturn(List.of(stale));
        when(runRepository.saveAndFlush(any(ExperimentRun.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generateAccessCode(researcherId, studyId, participant.getId());

        assertThat(stale.getStatus()).isEqualTo(ExperimentRunStatus.EXPIRED);
        assertThat(stale.getAccessCodeHash()).isNull();
    }

    @Test
    void hashCollisionRegeneratesTheCode() throws Exception {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        stubAuditResearcher();
        when(runRepository.saveAndFlush(any(ExperimentRun.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_runs_access_code_hash\""))
                .thenAnswer(inv -> inv.getArgument(0));

        AccessCodeResponse response = service.generateAccessCode(researcherId, studyId, participant.getId());

        ArgumentCaptor<ExperimentRun> runCaptor = ArgumentCaptor.forClass(ExperimentRun.class);
        verify(runRepository, times(2)).saveAndFlush(runCaptor.capture());
        List<ExperimentRun> attempts = runCaptor.getAllValues();
        assertThat(attempts.get(0).getAccessCodeHash()).isNotEqualTo(attempts.get(1).getAccessCodeHash());
        assertThat(attempts.get(1).getAccessCodeHash()).isEqualTo(sha256Hex(response.code()));
        verify(auditRepository, times(1)).save(any(ResearchAuditEvent.class));
    }

    @Test
    void givesUpAfterThreeHashCollisions() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        when(runRepository.saveAndFlush(any(ExperimentRun.class)))
                .thenThrow(new DataIntegrityViolationException("uk_runs_access_code_hash"));

        assertThatThrownBy(() -> service.generateAccessCode(researcherId, studyId, participant.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Could not generate a unique access code");
        verify(runRepository, times(3)).saveAndFlush(any());
    }

    @Test
    void hashCollisionIsDetectedByHibernateConstraintName() throws Exception {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        stubAuditResearcher();
        // Ni el mensaje ni la causa raiz nombran la restriccion: solo el nombre que expone Hibernate.
        DataIntegrityViolationException collision = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("duplicate key"),
                        "UK_RUNS_ACCESS_CODE_HASH"));
        when(runRepository.saveAndFlush(any(ExperimentRun.class)))
                .thenThrow(collision)
                .thenAnswer(inv -> inv.getArgument(0));

        AccessCodeResponse response = service.generateAccessCode(researcherId, studyId, participant.getId());

        ArgumentCaptor<ExperimentRun> runCaptor = ArgumentCaptor.forClass(ExperimentRun.class);
        verify(runRepository, times(2)).saveAndFlush(runCaptor.capture());
        assertThat(runCaptor.getAllValues().get(1).getAccessCodeHash()).isEqualTo(sha256Hex(response.code()));
        verify(auditRepository, times(1)).save(any(ResearchAuditEvent.class));
    }

    @Test
    void unrecognisedIntegrityViolationIsRethrownWhenIssuingACode() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        DataIntegrityViolationException other = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("fk"), "fk_runs_task"));
        when(runRepository.saveAndFlush(any(ExperimentRun.class))).thenThrow(other);

        assertThatThrownBy(() -> service.generateAccessCode(researcherId, studyId, participant.getId()))
                .isSameAs(other);
        verify(runRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void eachCodeAttemptRunsInANewTransaction() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        stubAuditResearcher();
        when(runRepository.saveAndFlush(any(ExperimentRun.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generateAccessCode(researcherId, studyId, participant.getId());

        verify(transactionManager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void openRunRaceIsReportedAsBusinessError() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        stubCodeGeneration(participant, protocol, 0);
        when(runRepository.saveAndFlush(any(ExperimentRun.class)))
                .thenThrow(new DataIntegrityViolationException("uk_runs_one_open_per_participant"));

        assertThatThrownBy(() -> service.generateAccessCode(researcherId, studyId, participant.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Participant already has an open run");
        verify(runRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void codeGenerationRequiresAnActiveProtocol() {
        StudyParticipant participant = new StudyParticipant(study, 1);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(participantRepository.findByIdAndStudyId(participant.getId(), studyId)).thenReturn(Optional.of(participant));
        when(protocolRepository.findFirstByStudyIdAndStatus(studyId, ProtocolStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateAccessCode(researcherId, studyId, participant.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Study has no active protocol");
    }

    // ---------------------------------------------------------------- revoke / cancel / exclude

    @Test
    void revokingAPendingCodeCancelsTheRunWithoutDeletingIt() {
        ExperimentRun run = pendingRun(1);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        ExperimentRunResponse response = service.revokeAccessCode(researcherId, studyId, run.getId());

        assertThat(response.status()).isEqualTo(ExperimentRunStatus.CANCELLED);
        assertThat(run.getAccessCodeHash()).isNull();
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
        verify(runRepository, never()).delete(any());
        ArgumentCaptor<ResearchAuditEvent> auditCaptor = ArgumentCaptor.forClass(ResearchAuditEvent.class);
        verify(auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("ACCESS_CODE_REVOKED");
    }

    @Test
    void revokeOnlyAppliesToPendingRuns() {
        ExperimentRun run = pendingRun(1);
        run.redeem(NOW);
        run.start(NOW);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.revokeAccessCode(researcherId, studyId, run.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only a pending access code can be revoked");
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.ACTIVE);
    }

    @Test
    void cancelsActiveRunWithReason() {
        ExperimentRun run = pendingRun(1);
        run.redeem(NOW);
        run.start(NOW);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        ExperimentRunResponse response = service.cancelRun(
                researcherId, studyId, run.getId(), new RunReasonRequest("El alumno abandono la sesion"));

        assertThat(response.status()).isEqualTo(ExperimentRunStatus.CANCELLED);
        assertThat(run.getFailureReason()).isEqualTo("El alumno abandono la sesion");
    }

    @Test
    void cancellationRejectsCompletedRuns() {
        ExperimentRun run = completedRun(1);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.cancelRun(
                researcherId, studyId, run.getId(), new RunReasonRequest("Motivo suficientemente largo")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only pending or active runs can be cancelled");
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
    }

    @Test
    void cancellingACancelledRunIsRejectedAndNotAudited() {
        ExperimentRun run = pendingRun(1);
        run.cancel("Motivo original de la cancelacion", NOW.minusSeconds(60));
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.cancelRun(
                researcherId, studyId, run.getId(), new RunReasonRequest("Un motivo distinto y posterior")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only pending or active runs can be cancelled");

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.CANCELLED);
        assertThat(run.getFailureReason()).isEqualTo("Motivo original de la cancelacion");
        assertThat(run.getCompletedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void excludesCompletedRunRecordingResearcherAndTimestamp() {
        ExperimentRun run = completedRun(1);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        ExperimentRunResponse response = service.excludeRun(
                researcherId, studyId, run.getId(), new RunReasonRequest("Duracion inconsistente con el servidor"));

        assertThat(response.excluded()).isTrue();
        assertThat(response.exclusionReason()).isEqualTo("Duracion inconsistente con el servidor");
        assertThat(response.status()).isEqualTo(ExperimentRunStatus.COMPLETED);
        assertThat(run.getExcludedAt()).isEqualTo(NOW);
        assertThat(run.getExcludedBy()).isSameAs(researcher);
        verify(runRepository, never()).delete(any());
    }

    @Test
    void exclusionRejectsPendingRuns() {
        ExperimentRun run = pendingRun(1);
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
        when(runRepository.findByIdAndParticipantStudyId(run.getId(), studyId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.excludeRun(
                researcherId, studyId, run.getId(), new RunReasonRequest("Motivo suficientemente largo")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only completed or failed runs can be excluded");
        assertThat(run.isExcluded()).isFalse();
    }

    @Test
    void runsOfAnotherResearchersStudyAreNotFound() {
        UUID otherStudyId = UUID.randomUUID();
        when(studyRepository.findByIdAndCreatedById(otherStudyId, researcherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listRuns(researcherId, otherStudyId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Study not found");
    }

    // ---------------------------------------------------------------- pseudonymity guard

    @Test
    void researchDtosNeverCarryStudentIdentity() {
        List<Class<?>> dtos = List.of(
                ParticipantResponse.class,
                AccessCodeResponse.class,
                ExperimentRunResponse.class,
                com.mvp.backend.research.application.dto.ResearchStudyResponse.class,
                StudyProtocolResponse.class);
        for (Class<?> dto : dtos) {
            for (var component : dto.getRecordComponents()) {
                assertThat(component.getName().toLowerCase())
                        .as("%s.%s", dto.getSimpleName(), component.getName())
                        .doesNotContain("student")
                        .doesNotContain("name")
                        .doesNotContain("email")
                        .doesNotContain("text");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void stubCodeGeneration(StudyParticipant participant, StudyProtocol protocol, long completedRuns) {
        when(studyRepository.findByIdAndCreatedById(studyId, researcherId)).thenReturn(Optional.of(study));
        when(participantRepository.findByIdAndStudyId(participant.getId(), studyId)).thenReturn(Optional.of(participant));
        when(protocolRepository.findFirstByStudyIdAndStatus(studyId, ProtocolStatus.ACTIVE)).thenReturn(Optional.of(protocol));
        when(runRepository.findByParticipantIdOrderByCreatedAtAsc(participant.getId())).thenReturn(List.of());
        when(runRepository.existsByParticipantIdAndStatusIn(eq(participant.getId()), anyCollection())).thenReturn(false);
        when(runRepository.countByParticipantIdAndStatus(participant.getId(), ExperimentRunStatus.COMPLETED))
                .thenReturn(completedRuns);
    }

    private void stubAuditResearcher() {
        when(researcherRepository.findById(researcherId)).thenReturn(Optional.of(researcher));
    }

    private StudyProtocol protocolWithTasks(ResearchStudy owner, int version) {
        StudyProtocol protocol = new StudyProtocol(owner, version);
        protocol.addTask(TaskVariant.TASK_A, "Consigna A");
        protocol.addTask(TaskVariant.TASK_B, "Consigna B");
        return protocol;
    }

    private ExperimentRun pendingRun(int participantNumber) {
        StudyParticipant participant = new StudyParticipant(study, participantNumber);
        StudyProtocol protocol = protocolWithTasks(study, 1);
        protocol.activate();
        return new ExperimentRun(participant, protocol, protocol.findTask(TaskVariant.TASK_A).orElseThrow(),
                participant.nextCondition(0), "b".repeat(64), NOW.plus(TTL), NOW);
    }

    private ExperimentRun completedRun(int participantNumber) {
        ExperimentRun run = pendingRun(participantNumber);
        run.redeem(NOW);
        run.start(NOW);
        run.complete("Texto final", 60_000L, UUID.randomUUID(), NOW.plusSeconds(60));
        return run;
    }

    private static String sha256Hex(String code) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
