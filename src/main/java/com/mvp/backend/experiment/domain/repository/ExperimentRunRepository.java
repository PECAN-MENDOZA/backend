package com.mvp.backend.experiment.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;

import jakarta.persistence.LockModeType;

public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, UUID> {

    Optional<ExperimentRun> findByIdAndParticipantStudentId(UUID runId, UUID studentId);

    // Toda mutacion desde el telefono (start/complete/cancel) toma el bloqueo de fila: la segunda
    // transaccion concurrente espera y relee el estado ya confirmado en vez de trabajar sobre una
    // copia obsoleta. Solo lecturas (active) usan la variante sin bloqueo.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExperimentRun r where r.id = :runId and r.participant.student.id = :studentId")
    Optional<ExperimentRun> findByIdAndParticipantStudentIdForUpdate(
            @Param("runId") UUID runId, @Param("studentId") UUID studentId);

    // Registro de incidencias en transaccion propia: el bloqueo evita que dos fallos simultaneos
    // lean el mismo contador y se pierda uno (incidentCount++ sobre una copia obsoleta).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExperimentRun r where r.id = :id")
    Optional<ExperimentRun> findByIdForUpdate(@Param("id") UUID id);

    Optional<ExperimentRun> findByIdAndParticipantStudyId(UUID runId, UUID studyId);

    // El indice unico parcial garantiza a lo sumo una ejecucion abierta por hash; si hubiera mas,
    // Spring Data lanza IncorrectResultSizeDataAccessException (fallo seguro).
    Optional<ExperimentRun> findByAccessCodeHashAndStatus(String hash, ExperimentRunStatus status);

    // Canje: bloquea la fila para que dos alumnos con el mismo codigo se serialicen; el segundo
    // relee el participante ya vinculado y recibe el error generico.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExperimentRun r where r.accessCodeHash = :hash and r.status = :status")
    Optional<ExperimentRun> findByAccessCodeHashAndStatusForUpdate(
            @Param("hash") String hash, @Param("status") ExperimentRunStatus status);

    Optional<ExperimentRun> findFirstByParticipantStudentIdAndStatus(UUID studentId, ExperimentRunStatus status);

    // Restauracion: ACTIVE primero, luego PENDING ya canjeada (la app se cerro antes de confirmar
    // el inicio); dentro de cada grupo, la mas reciente.
    @Query("""
            select r from ExperimentRun r
            where r.participant.student.id = :studentId
              and (r.status = 'ACTIVE' or (r.status = 'PENDING' and r.redeemedAt is not null))
            order by case when r.status = 'ACTIVE' then 0 else 1 end, r.redeemedAt desc, r.createdAt desc
            """)
    List<ExperimentRun> findRestorableByStudentId(@Param("studentId") UUID studentId);

    long countByParticipantIdAndStatus(UUID participantId, ExperimentRunStatus status);

    boolean existsByParticipantIdAndStatusIn(UUID participantId, Collection<ExperimentRunStatus> statuses);

    List<ExperimentRun> findByParticipantIdOrderByCreatedAtAsc(UUID participantId);

    List<ExperimentRun> findByParticipantStudyIdOrderByCreatedAtAsc(UUID studyId);
}
