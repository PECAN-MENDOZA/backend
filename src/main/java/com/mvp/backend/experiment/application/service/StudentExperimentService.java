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
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final ExperimentRunRepository runRepository;
    private final StudyParticipantRepository participantRepository;
    private final StudentRepository studentRepository;
    private final Clock clock;
    private final String backendVersion;

    // ponytail: limite por proceso, suficiente para el piloto con una sola instancia del backend
    // (cada alumno: <=5 canjes fallidos cada 5 min). Antes de habilitar varias instancias debe
    // pasar a almacenamiento compartido (BD o cache), porque cada JVM tendria su propio contador.
    private final ConcurrentHashMap<UUID, AttemptWindow> attempts = new ConcurrentHashMap<>();

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
     * cualquier otro alumno despues. Todo fallo (cuenta inexistente, codigo desconocido, vencido,
     * revocado o de otro alumno) responde el mismo mensaje generico para no revelar nada sobre el
     * codigo. Un codigo PENDING vencido se marca EXPIRED y ese cambio se conserva aunque el canje
     * falle ({@code noRollbackFor}).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public ExperimentRunResponse redeem(UUID studentId, RedeemAccessCodeRequest request) {
        Instant now = clock.instant();
        // La reserva es lo primero: se libera como fallo ante cualquier excepcion (finally).
        reserveAttempt(studentId, now);
        boolean succeeded = false;
        try {
            // El alumno se resuelve antes de tocar el codigo: una cuenta borrada no debe servir de
            // oraculo sobre la validez del codigo, y cuenta como intento fallido.
            Student student = studentRepository.findById(studentId)
                    .orElseThrow(() -> new BusinessException(INVALID_CODE));
            ExperimentRun run = redeemOrFail(student, AccessCode.hash(request.code()), now);
            succeeded = true;
            return ExperimentRunResponse.from(run);
        } finally {
            if (succeeded) {
                recordSuccess(studentId);
            } else {
                recordFailure(studentId);
            }
        }
    }

    private ExperimentRun redeemOrFail(Student student, String hash, Instant now) {
        // Bloqueo de fila: dos alumnos con el mismo codigo se serializan; el segundo relee el
        // participante ya vinculado (estado confirmado) y recibe el error generico.
        ExperimentRun run = runRepository.findByAccessCodeHashAndStatusForUpdate(hash, ExperimentRunStatus.PENDING)
                .orElseThrow(() -> new BusinessException(INVALID_CODE));
        if (run.getAccessCodeExpiresAt().isBefore(now)) {
            run.expire(now);
            runRepository.save(run);
            throw new BusinessException(INVALID_CODE);
        }
        StudyParticipant participant = run.getParticipant();
        boolean firstRedemption = participant.getStudent() == null;
        if (firstRedemption) {
            requireNoOtherParticipant(participant, student.getId());
        } else if (!participant.isLinkedTo(student.getId())) {
            throw new BusinessException(INVALID_CODE);
        }
        // El canje del dominio va ANTES de vincular: si su precondicion falla, nunca queda un
        // participante vinculado a medias (el cambio se confirmaria por noRollbackFor).
        invalidCodeOnDomainFailure(() -> run.redeem(now));
        if (firstRedemption) {
            invalidCodeOnDomainFailure(() -> participant.linkStudent(student));
        }
        return run;
    }

    /** Un alumno ocupa a lo sumo un participante por estudio (uk_study_student); se verifica antes de vincular. */
    private void requireNoOtherParticipant(StudyParticipant participant, UUID studentId) {
        boolean alreadyAnotherParticipant = participantRepository
                .findByStudyIdAndStudentId(participant.getStudy().getId(), studentId)
                .filter(other -> !other.getId().equals(participant.getId()))
                .isPresent();
        if (alreadyAnotherParticipant) {
            throw new BusinessException(INVALID_CODE);
        }
    }

    private static void invalidCodeOnDomainFailure(Runnable action) {
        try {
            action.run();
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

    /**
     * Una ejecucion ajena responde igual que una inexistente. Toma el bloqueo de fila (start,
     * complete y cancel escriben): la segunda transaccion concurrente espera y relee el estado ya
     * confirmado, asi que un start repetido conserva el primer {@code startedAt}, la misma clave de
     * finalizacion devuelve el resultado guardado y otra clave sobre una ejecucion COMPLETED
     * recibe "Run is not active" (400) en lugar de sobrescribir el texto del ganador.
     */
    private ExperimentRun requireOwnRun(UUID studentId, UUID runId) {
        return runRepository.findByIdAndParticipantStudentIdForUpdate(runId, studentId)
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

    // ------------------------------------------------------------ rate limit

    /** Ventana fija por alumno: fallos confirmados y reservas en vuelo (intentos aun sin resolver). */
    private record AttemptWindow(Instant windowStart, int failures, int inFlight) {

        boolean expired(Instant now) {
            return windowStart.plus(WINDOW).isBefore(now);
        }
    }

    /** Reserva un intento de forma atomica; lanza si fallos + en vuelo ya alcanzan el maximo. */
    private void reserveAttempt(UUID studentId, Instant now) {
        evictExpired(now);
        boolean[] reserved = {false};
        attempts.compute(studentId, (id, current) -> {
            AttemptWindow base = (current == null || current.expired(now)) ? new AttemptWindow(now, 0, 0) : current;
            if (base.failures() + base.inFlight() >= MAX_FAILURES) {
                return base; // sin cambios: bloqueado
            }
            reserved[0] = true;
            return new AttemptWindow(base.windowStart(), base.failures(), base.inFlight() + 1);
        });
        if (!reserved[0]) {
            throw new BusinessException(TOO_MANY_ATTEMPTS);
        }
    }

    private void recordFailure(UUID studentId) {
        attempts.computeIfPresent(studentId, (id, w) ->
                new AttemptWindow(w.windowStart(), w.failures() + 1, Math.max(0, w.inFlight() - 1)));
    }

    private void recordSuccess(UUID studentId) {
        attempts.remove(studentId);
    }

    private void evictExpired(Instant now) {
        attempts.entrySet().removeIf(e -> e.getValue().inFlight() == 0 && e.getValue().expired(now));
    }

    /** Solo para pruebas: alumnos con ventana de intentos viva. */
    int trackedStudents() {
        return attempts.size();
    }
}
