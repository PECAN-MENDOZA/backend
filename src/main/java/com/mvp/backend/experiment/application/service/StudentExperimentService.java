package com.mvp.backend.experiment.application.service;

import static com.mvp.backend.shared.persistence.ConstraintViolations.violates;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.experiment.application.dto.CancelExperimentRequest;
import com.mvp.backend.experiment.application.dto.CompleteExperimentRequest;
import com.mvp.backend.experiment.application.dto.ExperimentRunResponse;
import com.mvp.backend.experiment.application.dto.RedeemAccessCodeRequest;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Ciclo de vida de una ejecucion desde el telefono del alumno: canje del codigo, inicio, restauracion,
 * finalizacion y cancelacion. El alumno nunca elige condicion, consigna ni orden; solo ve la
 * asignacion que el investigador fijo. Ningun codigo, token ni texto final se registra en logs.
 */
@Service
public class StudentExperimentService {

    static final String INVALID_CODE = "Access code is invalid or unavailable";
    static final String TOO_MANY_ATTEMPTS = "Too many failed redemption attempts, try again later";
    static final String DURATION_INCIDENT = "DURATION_INCONSISTENT";
    /** Nombre de la restriccion UNIQUE de {@code experiment_runs.completion_key} (V8 y entidad). */
    static final String COMPLETION_KEY_CONSTRAINT = "uk_runs_completion_key";

    private static final long DURATION_TOLERANCE_MS = 300_000L;
    private static final int MAX_FAILED_REDEMPTIONS = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    private final ExperimentRunRepository runRepository;
    private final StudyParticipantRepository participantRepository;
    private final StudentRepository studentRepository;
    private final Clock clock;
    private final String backendVersion;

    // ponytail: limite por proceso, suficiente para el piloto con una sola instancia del backend
    // (cada alumno: <=5 canjes fallidos cada 5 min). Antes de habilitar varias instancias debe
    // pasar a almacenamiento compartido (BD o cache), porque cada JVM tendria su propio contador.
    private final ConcurrentHashMap<UUID, AttemptWindow> failedRedemptions = new ConcurrentHashMap<>();

    public StudentExperimentService(
            ExperimentRunRepository runRepository,
            StudyParticipantRepository participantRepository,
            StudentRepository studentRepository,
            Clock clock,
            @Value("${app.build-version:local}") String backendVersion) {
        this.runRepository = runRepository;
        this.participantRepository = participantRepository;
        this.studentRepository = studentRepository;
        this.clock = clock;
        this.backendVersion = backendVersion;
    }

    /**
     * Canjea el codigo: vincula al alumno con el participante en el primer canje y rechaza a
     * cualquier otro alumno despues. Todo fallo (codigo desconocido, vencido, revocado, de otro
     * alumno) responde el mismo mensaje generico para no revelar nada sobre el codigo. Un codigo
     * PENDING vencido se marca EXPIRED y ese cambio se conserva aunque el canje falle
     * ({@code noRollbackFor}).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public ExperimentRunResponse redeem(UUID studentId, RedeemAccessCodeRequest request) {
        Instant now = clock.instant();
        AttemptWindow window = failedRedemptions.get(studentId);
        if (window != null && window.blocks(now)) {
            throw new BusinessException(TOO_MANY_ATTEMPTS);
        }
        try {
            ExperimentRun run = redeemOrFail(studentId, AccessCode.hash(request.code()), now);
            failedRedemptions.remove(studentId);
            return ExperimentRunResponse.from(run);
        } catch (BusinessException e) {
            failedRedemptions.compute(studentId, (id, current) -> AttemptWindow.recordFailure(current, now));
            throw e;
        }
    }

    private ExperimentRun redeemOrFail(UUID studentId, String hash, Instant now) {
        ExperimentRun run = runRepository.findByAccessCodeHashAndStatus(hash, ExperimentRunStatus.PENDING)
                .orElseThrow(() -> new BusinessException(INVALID_CODE));
        if (run.getAccessCodeExpiresAt().isBefore(now)) {
            run.expire(now);
            runRepository.save(run);
            throw new BusinessException(INVALID_CODE);
        }
        StudyParticipant participant = run.getParticipant();
        if (participant.getStudent() == null) {
            bindStudent(participant, studentId);
        } else if (!participant.isLinkedTo(studentId)) {
            throw new BusinessException(INVALID_CODE);
        }
        try {
            run.redeem(now);
        } catch (IllegalStateException e) {
            throw new BusinessException(INVALID_CODE);
        }
        return run;
    }

    /** Un alumno ocupa a lo sumo un participante por estudio (uk_study_student); se verifica antes de vincular. */
    private void bindStudent(StudyParticipant participant, UUID studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new NotFoundException("Student not found"));
        boolean alreadyAnotherParticipant = participantRepository
                .findByStudyIdAndStudentId(participant.getStudy().getId(), studentId)
                .filter(other -> !other.getId().equals(participant.getId()))
                .isPresent();
        if (alreadyAnotherParticipant) {
            throw new BusinessException(INVALID_CODE);
        }
        try {
            participant.linkStudent(student);
        } catch (IllegalStateException e) {
            throw new BusinessException(INVALID_CODE);
        }
    }

    /** Fija la hora de inicio del servidor y la version del backend; idempotente sobre ACTIVE. */
    @Transactional
    public ExperimentRunResponse start(UUID studentId, UUID runId) {
        ExperimentRun run = requireOwnRun(studentId, runId);
        domain(() -> run.start(clock.instant()));
        run.recordBackendVersion(backendVersion);
        return ExperimentRunResponse.from(run);
    }

    /** Ejecucion a restaurar: ACTIVE (pantalla de tarea) o PENDING ya canjeada (pantalla de confirmacion). */
    @Transactional(readOnly = true)
    public ExperimentRunResponse active(UUID studentId) {
        return runRepository.findRestorableByStudentId(studentId).stream()
                .findFirst()
                .map(ExperimentRunResponse::from)
                .orElseThrow(() -> new NotFoundException("No experiment run to restore"));
    }

    /**
     * Guarda el texto y la duracion monotonica reportada tal cual (medida primaria de PPM). Una
     * duracion mayor que la transcurrida en el servidor (+ tolerancia) se registra como incidencia,
     * nunca se rechaza ni se corrige. Idempotente por clave de finalizacion; la misma clave en otra
     * ejecucion es un conflicto (409).
     */
    @Transactional
    public ExperimentRunResponse complete(UUID studentId, UUID runId, CompleteExperimentRequest request) {
        ExperimentRun run = requireOwnRun(studentId, runId);
        if (run.isCompleted() && request.completionKey().equals(run.getCompletionKey())) {
            return ExperimentRunResponse.from(run);
        }
        if (!run.isActive()) {
            throw new BusinessException("Run is not active");
        }
        Instant now = clock.instant();
        long serverElapsedMs = Duration.between(run.getStartedAt(), now).toMillis();
        if (request.durationMs() > serverElapsedMs + DURATION_TOLERANCE_MS) {
            run.recordIncident(DURATION_INCIDENT);
        }
        domain(() -> run.complete(request.finalText(), request.durationMs(), request.completionKey(), now));
        run.recordAppVersion(request.appVersion());
        try {
            // Flush explicito: la clave duplicada debe detectarse aqui y no al confirmar la transaccion.
            runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, COMPLETION_KEY_CONSTRAINT)) {
                throw new ConflictException("Completion key already used by another run");
            }
            throw e;
        }
        return ExperimentRunResponse.from(run);
    }

    /** Cancelacion por el alumno con un motivo fijo; idempotente sobre CANCELLED (el dominio no lo reescribe). */
    @Transactional
    public void cancel(UUID studentId, UUID runId, CancelExperimentRequest request) {
        ExperimentRun run = requireOwnRun(studentId, runId);
        domain(() -> run.cancel(request.reason().description(), clock.instant()));
    }

    // ------------------------------------------------------------------ helpers

    /** Una ejecucion ajena responde igual que una inexistente. */
    private ExperimentRun requireOwnRun(UUID studentId, UUID runId) {
        return runRepository.findByIdAndParticipantStudentId(runId, studentId)
                .orElseThrow(() -> new NotFoundException("Run not found"));
    }

    /** Traduce las reglas del dominio (IllegalState/IllegalArgument) a errores 400 con el mismo mensaje. */
    private static void domain(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    /** Ventana fija de fallos: arranca con el primer fallo y se reinicia al vencer. */
    record AttemptWindow(Instant startedAt, int failures) {

        static AttemptWindow recordFailure(AttemptWindow current, Instant now) {
            if (current == null || current.expired(now)) {
                return new AttemptWindow(now, 1);
            }
            return new AttemptWindow(current.startedAt, current.failures + 1);
        }

        boolean blocks(Instant now) {
            return !expired(now) && failures >= MAX_FAILED_REDEMPTIONS;
        }

        private boolean expired(Instant now) {
            return !now.isBefore(startedAt.plus(FAILURE_WINDOW));
        }
    }
}
