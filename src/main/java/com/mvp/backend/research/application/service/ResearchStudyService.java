package com.mvp.backend.research.application.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.config.ResearchProperties;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AccessCodeResponse;
import com.mvp.backend.research.application.dto.CreateProtocolRequest;
import com.mvp.backend.research.application.dto.CreateStudyRequest;
import com.mvp.backend.research.application.dto.ExperimentRunResponse;
import com.mvp.backend.research.application.dto.NextSessionResponse;
import com.mvp.backend.research.application.dto.ParticipantResponse;
import com.mvp.backend.research.application.dto.ResearchStudyResponse;
import com.mvp.backend.research.application.dto.RunReasonRequest;
import com.mvp.backend.research.application.dto.StudyProtocolResponse;
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

/**
 * Administracion de estudios por parte del investigador. Todo se expresa en pseudonimos
 * {@code P-nnn}: ninguna respuesta ni evento de auditoria contiene identidad del alumno,
 * codigos en claro ni texto producido por participantes.
 */
@Service
public class ResearchStudyService {

    private static final List<ExperimentRunStatus> OPEN_STATUSES =
            List.of(ExperimentRunStatus.PENDING, ExperimentRunStatus.ACTIVE);
    private static final int MAX_CODE_ATTEMPTS = 3;
    private static final String REVOKED_REASON = "Access code revoked by the researcher";

    private final ResearchStudyRepository studyRepository;
    private final StudyProtocolRepository protocolRepository;
    private final StudyParticipantRepository participantRepository;
    private final ExperimentRunRepository runRepository;
    private final ResearchAuditEventRepository auditRepository;
    private final ResearcherRepository researcherRepository;
    private final TransactionTemplate transactionTemplate;
    private final ResearchProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public ResearchStudyService(
            ResearchStudyRepository studyRepository,
            StudyProtocolRepository protocolRepository,
            StudyParticipantRepository participantRepository,
            ExperimentRunRepository runRepository,
            ResearchAuditEventRepository auditRepository,
            ResearcherRepository researcherRepository,
            TransactionTemplate transactionTemplate,
            ResearchProperties properties,
            Clock clock) {
        this.studyRepository = studyRepository;
        this.protocolRepository = protocolRepository;
        this.participantRepository = participantRepository;
        this.runRepository = runRepository;
        this.auditRepository = auditRepository;
        this.researcherRepository = researcherRepository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ studies

    @Transactional(readOnly = true)
    public List<ResearchStudyResponse> listStudies(UUID researcherId) {
        return studyRepository.findByCreatedByIdOrderByCreatedAtDesc(researcherId).stream()
                .map(this::toStudyResponse)
                .toList();
    }

    @Transactional
    public ResearchStudyResponse createStudy(UUID researcherId, CreateStudyRequest request) {
        Researcher researcher = requireResearcher(researcherId);
        String code = request.code().strip();
        if (studyRepository.existsByCode(code)) {
            throw new ConflictException("Study code already exists");
        }
        ResearchStudy study = studyRepository.save(new ResearchStudy(code, request.title().strip(), researcher));
        audit(researcher, study, "STUDY_CREATED", study.getId(), "code=" + code);
        return toStudyResponse(study);
    }

    // ---------------------------------------------------------------- protocols

    @Transactional
    public StudyProtocolResponse createProtocol(UUID researcherId, UUID studyId, CreateProtocolRequest request) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        requireNotClosed(study);
        int version = protocolRepository.findFirstByStudyIdOrderByVersionDesc(studyId)
                .map(StudyProtocol::getVersion)
                .orElse(0) + 1;
        StudyProtocol protocol = new StudyProtocol(study, version);
        domain(() -> {
            protocol.addTask(TaskVariant.TASK_A, request.taskAPrompt().strip());
            protocol.addTask(TaskVariant.TASK_B, request.taskBPrompt().strip());
        });
        StudyProtocol saved = protocolRepository.save(protocol);
        audit(requireResearcher(researcherId), study, "PROTOCOL_CREATED", saved.getId(), "version=" + version);
        return StudyProtocolResponse.from(saved);
    }

    @Transactional
    public StudyProtocolResponse activateProtocol(UUID researcherId, UUID studyId, UUID protocolId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        StudyProtocol protocol = protocolRepository.findByIdAndStudyId(protocolId, studyId)
                .orElseThrow(() -> new NotFoundException("Protocol not found"));
        requireNotClosed(study);
        if (protocol.isActive()) {
            return StudyProtocolResponse.from(protocol); // idempotente
        }
        for (TaskVariant variant : TaskVariant.values()) {
            boolean defined = protocol.findTask(variant)
                    .map(task -> task.getPromptText() != null && !task.getPromptText().isBlank())
                    .orElse(false);
            if (!defined) {
                throw new BusinessException("Protocol must define a non-blank prompt for " + variant);
            }
        }
        protocolRepository.findFirstByStudyIdAndStatus(studyId, ProtocolStatus.ACTIVE)
                .filter(active -> !active.getId().equals(protocol.getId()))
                .ifPresent(active -> domain(active::retire));
        domain(protocol::activate);
        if (study.getStatus() == StudyStatus.DRAFT) {
            study.activate();
        }
        audit(requireResearcher(researcherId), study, "PROTOCOL_ACTIVATED", protocol.getId(),
                "version=" + protocol.getVersion());
        return StudyProtocolResponse.from(protocol);
    }

    // ------------------------------------------------------------- participants

    @Transactional(readOnly = true)
    public List<ParticipantResponse> listParticipants(UUID researcherId, UUID studyId) {
        requireOwnedStudy(researcherId, studyId);
        return participantRepository.findByStudyIdOrderByPseudonymAsc(studyId).stream()
                .map(this::toParticipantResponse)
                .toList();
    }

    /** Bloquea el estudio, consume el correlativo y persiste el participante en una sola unidad. */
    @Transactional
    public ParticipantResponse createParticipant(UUID researcherId, UUID studyId) {
        ResearchStudy study = studyRepository.findOwnedForUpdate(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
        if (!study.isActive()) {
            throw new BusinessException("Study is not active");
        }
        int number = study.allocateParticipantNumber();
        StudyParticipant participant = participantRepository.save(new StudyParticipant(study, number));
        studyRepository.save(study);
        audit(requireResearcher(researcherId), study, "PARTICIPANT_CREATED", participant.getId(),
                "pseudonym=" + participant.getPseudonym());
        return new ParticipantResponse(
                participant.getId(),
                participant.getPseudonym(),
                participant.getSequence(),
                0,
                false,
                false,
                new NextSessionResponse(TaskVariant.TASK_A, participant.nextCondition(0)),
                participant.getCreatedAt());
    }

    // ------------------------------------------------------------- access codes

    /**
     * Emite un codigo de un solo uso para la unica sesion permitida al participante. Cada intento
     * corre en su propia transaccion: una colision del hash (uk_runs_access_code_hash) aborta la
     * transaccion en PostgreSQL, por lo que el reintento debe empezar una nueva.
     */
    public AccessCodeResponse generateAccessCode(UUID researcherId, UUID studyId, UUID participantId) {
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> issueAccessCode(researcherId, studyId, participantId));
            } catch (DataIntegrityViolationException e) {
                String cause = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
                if (cause.contains("uk_runs_one_open_per_participant")) {
                    throw new BusinessException("Participant already has an open run");
                }
                if (!cause.contains("uk_runs_access_code_hash")) {
                    throw e;
                }
                // Colision de hash (probabilidad ~2^-256): se regenera el codigo en una transaccion nueva.
            }
        }
        throw new BusinessException("Could not generate a unique access code");
    }

    private AccessCodeResponse issueAccessCode(UUID researcherId, UUID studyId, UUID participantId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        StudyParticipant participant = participantRepository.findByIdAndStudyId(participantId, studyId)
                .orElseThrow(() -> new NotFoundException("Participant not found"));
        if (!study.isActive()) {
            throw new BusinessException("Study is not active");
        }
        StudyProtocol protocol = protocolRepository.findFirstByStudyIdAndStatus(studyId, ProtocolStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException("Study has no active protocol"));

        Instant now = clock.instant();
        expireStaleCodes(participantId, now);
        if (runRepository.existsByParticipantIdAndStatusIn(participantId, OPEN_STATUSES)) {
            throw new BusinessException("Participant already has an open run");
        }
        long completed = runRepository.countByParticipantIdAndStatus(participantId, ExperimentRunStatus.COMPLETED);
        NextSessionResponse next = nextSession(participant, completed)
                .orElseThrow(() -> new BusinessException("Participant already completed both conditions"));
        ProtocolTask task = protocol.findTask(next.task())
                .orElseThrow(() -> new BusinessException("Active protocol is missing " + next.task()));

        String code = AccessCode.generate(secureRandom);
        Instant expiresAt = now.plus(properties.accessCodeTtl());
        ExperimentRun run = runRepository.saveAndFlush(
                new ExperimentRun(participant, protocol, task, next.condition(), AccessCode.hash(code), expiresAt, now));
        audit(requireResearcher(researcherId), study, "ACCESS_CODE_GENERATED", run.getId(),
                "pseudonym=" + participant.getPseudonym() + ", task=" + next.task()
                        + ", condition=" + next.condition() + ", expiresAt=" + expiresAt);
        return new AccessCodeResponse(
                run.getId(),
                participant.getId(),
                participant.getPseudonym(),
                code,
                expiresAt,
                next.task(),
                next.condition());
    }

    /** Un codigo PENDING nunca canjeado y ya vencido deja de bloquear al participante. */
    private void expireStaleCodes(UUID participantId, Instant now) {
        for (ExperimentRun run : runRepository.findByParticipantIdOrderByCreatedAtAsc(participantId)) {
            if (run.getStatus() == ExperimentRunStatus.PENDING
                    && run.getRedeemedAt() == null
                    && run.getAccessCodeExpiresAt().isBefore(now)) {
                run.expire(now);
                runRepository.save(run);
            }
        }
    }

    @Transactional
    public ExperimentRunResponse revokeAccessCode(UUID researcherId, UUID studyId, UUID runId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        ExperimentRun run = requireRun(studyId, runId);
        if (run.getStatus() != ExperimentRunStatus.PENDING) {
            throw new BusinessException("Only a pending access code can be revoked");
        }
        run.cancel(REVOKED_REASON, clock.instant());
        audit(requireResearcher(researcherId), study, "ACCESS_CODE_REVOKED", run.getId(), null);
        return ExperimentRunResponse.from(run);
    }

    // --------------------------------------------------------------------- runs

    @Transactional(readOnly = true)
    public List<ExperimentRunResponse> listRuns(UUID researcherId, UUID studyId) {
        requireOwnedStudy(researcherId, studyId);
        return runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId).stream()
                .map(ExperimentRunResponse::from)
                .toList();
    }

    @Transactional
    public ExperimentRunResponse cancelRun(UUID researcherId, UUID studyId, UUID runId, RunReasonRequest request) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        ExperimentRun run = requireRun(studyId, runId);
        domain(() -> run.cancel(request.reason(), clock.instant()));
        audit(requireResearcher(researcherId), study, "RUN_CANCELLED", run.getId(), null);
        return ExperimentRunResponse.from(run);
    }

    @Transactional
    public ExperimentRunResponse excludeRun(UUID researcherId, UUID studyId, UUID runId, RunReasonRequest request) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        ExperimentRun run = requireRun(studyId, runId);
        Researcher researcher = requireResearcher(researcherId);
        domain(() -> run.exclude(request.reason(), researcher, clock.instant()));
        audit(researcher, study, "RUN_EXCLUDED", run.getId(), null);
        return ExperimentRunResponse.from(run);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<NextSessionResponse> nextSession(StudyParticipant participant, long completed) {
        if (participant.hasCompletedProtocol(completed)) {
            return Optional.empty();
        }
        TaskVariant task = completed == 0 ? TaskVariant.TASK_A : TaskVariant.TASK_B;
        ExperimentCondition condition = participant.nextCondition(completed);
        return Optional.of(new NextSessionResponse(task, condition));
    }

    private ParticipantResponse toParticipantResponse(StudyParticipant participant) {
        long completed = runRepository.countByParticipantIdAndStatus(participant.getId(), ExperimentRunStatus.COMPLETED);
        boolean open = runRepository.existsByParticipantIdAndStatusIn(participant.getId(), OPEN_STATUSES);
        return new ParticipantResponse(
                participant.getId(),
                participant.getPseudonym(),
                participant.getSequence(),
                completed,
                participant.hasCompletedProtocol(completed),
                open,
                nextSession(participant, completed).orElse(null),
                participant.getCreatedAt());
    }

    private ResearchStudyResponse toStudyResponse(ResearchStudy study) {
        Integer activeVersion = protocolRepository.findFirstByStudyIdAndStatus(study.getId(), ProtocolStatus.ACTIVE)
                .map(StudyProtocol::getVersion)
                .orElse(null);
        return ResearchStudyResponse.from(study, activeVersion);
    }

    /** Un estudio ajeno responde igual que uno inexistente para no permitir enumerarlos. */
    private ResearchStudy requireOwnedStudy(UUID researcherId, UUID studyId) {
        return studyRepository.findByIdAndCreatedById(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
    }

    private ExperimentRun requireRun(UUID studyId, UUID runId) {
        return runRepository.findByIdAndParticipantStudyId(runId, studyId)
                .orElseThrow(() -> new NotFoundException("Run not found"));
    }

    private Researcher requireResearcher(UUID researcherId) {
        return researcherRepository.findById(researcherId)
                .orElseThrow(() -> new NotFoundException("Researcher not found"));
    }

    private static void requireNotClosed(ResearchStudy study) {
        if (study.getStatus() == StudyStatus.CLOSED) {
            throw new BusinessException("Study is closed");
        }
    }

    private void audit(Researcher researcher, ResearchStudy study, String action, UUID targetId, String detail) {
        auditRepository.save(new ResearchAuditEvent(researcher, study, action, targetId, detail));
    }

    /** Traduce las reglas del dominio (IllegalState/IllegalArgument) a errores 400 con el mismo mensaje. */
    private static void domain(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }
}
