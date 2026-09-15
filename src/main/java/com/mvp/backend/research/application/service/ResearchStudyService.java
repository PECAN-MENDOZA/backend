package com.mvp.backend.research.application.service;

import static com.mvp.backend.shared.persistence.ConstraintViolations.violates;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
    private static final String ACCESS_CODE_HASH_CONSTRAINT = "uk_runs_access_code_hash";
    private static final String ONE_OPEN_RUN_CONSTRAINT = "uk_runs_one_open_per_participant";
    /** Nombre explícito de la restricción UNIQUE de {@code research_studies.code} en V8. */
    private static final String STUDY_CODE_CONSTRAINT = "uk_study_code";
    /** {@code UNIQUE (study_id, version)} de {@code study_protocols} en V8. */
    private static final String PROTOCOL_VERSION_CONSTRAINT = "uk_protocol_version";

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
            PlatformTransactionManager transactionManager,
            ResearchProperties properties,
            Clock clock) {
        this.studyRepository = studyRepository;
        this.protocolRepository = protocolRepository;
        this.participantRepository = participantRepository;
        this.runRepository = runRepository;
        this.auditRepository = auditRepository;
        this.researcherRepository = researcherRepository;
        // Cada intento de emision debe correr en una transaccion propia (ver generateAccessCode).
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        ResearchStudy study;
        try {
            study = studyRepository.saveAndFlush(new ResearchStudy(code, request.title().strip(), researcher));
        } catch (DataIntegrityViolationException e) {
            // Carrera entre el existsByCode y el INSERT: solo esta restriccion se traduce a 409.
            if (violates(e, STUDY_CODE_CONSTRAINT)) {
                throw new ConflictException("Study code already exists");
            }
            throw e;
        }
        audit(researcher, study, "STUDY_CREATED", study.getId(), "code=" + code);
        return toStudyResponse(study);
    }

    /** Cierra la recogida de datos (spec §9.2): sin nuevos participantes, codigos ni protocolos. */
    @Transactional
    public ResearchStudyResponse closeStudy(UUID researcherId, UUID studyId) {
        ResearchStudy study = studyRepository.findOwnedForUpdate(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
        if (study.getStatus() == StudyStatus.CLOSED) {
            throw new BusinessException("Study is already closed");
        }
        domain(study::close);
        audit(requireResearcher(researcherId), study, "STUDY_CLOSED", study.getId(), null);
        return toStudyResponse(study);
    }

    // ---------------------------------------------------------------- protocols

    /**
     * Bloquea el estudio para que dos creaciones concurrentes no calculen la misma version; si aun asi
     * la restriccion {@code uk_protocol_version} salta, se responde 409 y no un 500.
     */
    @Transactional
    public StudyProtocolResponse createProtocol(UUID researcherId, UUID studyId, CreateProtocolRequest request) {
        ResearchStudy study = studyRepository.findOwnedForUpdate(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
        requireNotClosed(study);
        int version = protocolRepository.findFirstByStudyIdOrderByVersionDesc(studyId)
                .map(StudyProtocol::getVersion)
                .orElse(0) + 1;
        StudyProtocol protocol = new StudyProtocol(study, version);
        domain(() -> {
            protocol.addTask(TaskVariant.TASK_A, request.taskAPrompt().strip());
            protocol.addTask(TaskVariant.TASK_B, request.taskBPrompt().strip());
        });
        StudyProtocol saved;
        try {
            saved = protocolRepository.saveAndFlush(protocol);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, PROTOCOL_VERSION_CONSTRAINT)) {
                throw new ConflictException("Protocol version " + version + " already exists, retry");
            }
            throw e;
        }
        audit(requireResearcher(researcherId), study, "PROTOCOL_CREATED", saved.getId(), "version=" + version);
        return StudyProtocolResponse.from(saved);
    }

    /**
     * Bloquea el estudio (PESSIMISTIC_WRITE) para serializar activaciones concurrentes: el retiro
     * de la version vigente se vacia a la BD antes de activar la nueva, asi nunca coexisten dos ACTIVE.
     */
    @Transactional
    public StudyProtocolResponse activateProtocol(UUID researcherId, UUID studyId, UUID protocolId) {
        ResearchStudy study = studyRepository.findOwnedForUpdate(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
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
                .ifPresent(active -> {
                    domain(active::retire);
                    protocolRepository.saveAndFlush(active);
                });
        domain(protocol::activate);
        if (study.getStatus() == StudyStatus.DRAFT) {
            study.activate();
        }
        audit(requireResearcher(researcherId), study, "PROTOCOL_ACTIVATED", protocol.getId(),
                "version=" + protocol.getVersion());
        return StudyProtocolResponse.from(protocol);
    }

    /** Historial de versiones, mas reciente primero (spec panel investigador: prompts activos y de baja). */
    @Transactional(readOnly = true)
    public List<StudyProtocolResponse> listProtocols(UUID researcherId, UUID studyId) {
        requireOwnedStudy(researcherId, studyId);
        return protocolRepository.findByStudyIdOrderByVersionDesc(studyId).stream()
                .map(StudyProtocolResponse::from)
                .toList();
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
                if (violates(e, ONE_OPEN_RUN_CONSTRAINT)) {
                    throw new BusinessException("Participant already has an open run");
                }
                if (!violates(e, ACCESS_CODE_HASH_CONSTRAINT)) {
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

    /**
     * Un codigo PENDING ya vencido deja de bloquear al participante, tanto si nunca se canjeo como si
     * se canjeo y nunca se inicio (el telefono se cerro en la pantalla de confirmacion).
     */
    private void expireStaleCodes(UUID participantId, Instant now) {
        for (ExperimentRun run : runRepository.findByParticipantIdOrderByCreatedAtAsc(participantId)) {
            if (run.getStatus() == ExperimentRunStatus.PENDING
                    && run.getAccessCodeExpiresAt().isBefore(now)) {
                run.expire(now);
                runRepository.save(run);
            }
        }
    }

    @Transactional
    public ExperimentRunResponse revokeAccessCode(UUID researcherId, UUID studyId, UUID runId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        ExperimentRun run = requireRunForUpdate(studyId, runId);
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
        ExperimentRun run = requireRunForUpdate(studyId, runId);
        // run.cancel es idempotente sobre CANCELLED; aqui una segunda cancelacion no debe
        // aceptar otro motivo ni generar un nuevo evento de auditoria.
        if (!OPEN_STATUSES.contains(run.getStatus())) {
            throw new BusinessException("Only pending or active runs can be cancelled");
        }
        domain(() -> run.cancel(request.reason(), clock.instant()));
        audit(requireResearcher(researcherId), study, "RUN_CANCELLED", run.getId(), null);
        return ExperimentRunResponse.from(run);
    }

    /** Fallo tecnico declarado por el investigador (p. ej. la IA no respondio durante toda la sesion). */
    @Transactional
    public ExperimentRunResponse failRunTechnically(UUID researcherId, UUID studyId, UUID runId, RunReasonRequest request) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        ExperimentRun run = requireRunForUpdate(studyId, runId);
        // failTechnically es idempotente sobre TECHNICAL_FAILURE; una segunda declaracion no debe
        // aceptar otro motivo ni generar un nuevo evento de auditoria.
        if (!OPEN_STATUSES.contains(run.getStatus())) {
            throw new BusinessException("Only pending or active runs can fail technically");
        }
        domain(() -> run.failTechnically(request.reason(), clock.instant()));
        audit(requireResearcher(researcherId), study, "RUN_TECHNICAL_FAILURE", run.getId(), null);
        return ExperimentRunResponse.from(run);
    }

    @Transactional
    public ExperimentRunResponse excludeRun(UUID researcherId, UUID studyId, UUID runId, RunReasonRequest request) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        ExperimentRun run = requireRunForUpdate(studyId, runId);
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
        List<ExperimentRun> runs = runRepository.findByParticipantIdOrderByCreatedAtAsc(participant.getId());
        Instant now = clock.instant();
        long completed = runs.stream().filter(run -> run.getStatus() == ExperimentRunStatus.COMPLETED).count();
        boolean open = runs.stream().anyMatch(run -> isOpen(run, now));
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

    /**
     * ACTIVE siempre cuenta; PENDING solo si el codigo fue canjeado o aun no vencio. Un codigo
     * vencido y nunca canjeado no bloquea al participante (se marca EXPIRED al emitir el siguiente).
     */
    private static boolean isOpen(ExperimentRun run, Instant now) {
        return switch (run.getStatus()) {
            case ACTIVE -> true;
            case PENDING -> run.getRedeemedAt() != null || run.getAccessCodeExpiresAt().isAfter(now);
            default -> false;
        };
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

    /**
     * Toda transicion del investigador toma el bloqueo de fila: si el telefono completo la ejecucion
     * entre la lectura y la escritura, aqui se relee COMPLETED y la transicion se rechaza (400).
     */
    private ExperimentRun requireRunForUpdate(UUID studyId, UUID runId) {
        return runRepository.findByIdAndParticipantStudyIdForUpdate(runId, studyId)
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
