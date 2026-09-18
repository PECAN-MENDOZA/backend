package com.mvp.backend.correction.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.correction.domain.model.CorrectionSession;

import jakarta.persistence.LockModeType;

public interface CorrectionSessionRepository extends JpaRepository<CorrectionSession, UUID> {

    interface AcceptanceSummaryProjection {
        Long getTotalSessions();

        Long getAcceptedSessions();

        Long getRejectedSessions();

        Long getUnansweredSessions();

        Long getEditedSessions();
    }

    Optional<CorrectionSession> findByIdAndStudentId(UUID id, UUID studentId);

    // El feedback se serializa por sesion: dos envios concurrentes (reintento del teclado, undo)
    // se aplican uno tras otro sobre el estado ya confirmado y no sobre una copia obsoleta.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CorrectionSession s where s.id = :id and s.student.id = :studentId")
    Optional<CorrectionSession> findByIdAndStudentIdForUpdate(@Param("id") UUID id, @Param("studentId") UUID studentId);

    Page<CorrectionSession> findByStudentIdOrderByCreatedAtDesc(UUID studentId, Pageable pageable);

    // Sesiones de correccion pedidas sobre oraciones de una prueba (resultados y exportacion).
    List<CorrectionSession> findByTestResponseIdIn(List<UUID> testResponseIds);

    long countByTestResponseId(UUID testResponseId);

    // Sesiones de un grupo de alumnos en [start, end), las mas recientes primero (panel del docente).
    // Trae de una vez la cadena respuesta -> oracion / intento -> prueba (todas *-a-uno) para que
    // CorrectionFacts.assistance/testCode no disparen una consulta por sesion.
    @Query("""
            select s from CorrectionSession s
            left join fetch s.testResponse tr
            left join fetch tr.sentence
            left join fetch tr.attempt a
            left join fetch a.test
            where s.student.id in :studentIds
              and s.createdAt >= :start
              and s.createdAt < :end
            order by s.createdAt desc
            """)
    List<CorrectionSession> findInPeriodByStudentIds(
            @Param("studentIds") List<UUID> studentIds,
            @Param("start") Instant start,
            @Param("end") Instant end,
            Pageable pageable);

    // Ultima actividad de cada alumno sin limite de periodo: filas [studentId, max(createdAt)].
    @Query("""
            select s.student.id, max(s.createdAt)
            from CorrectionSession s
            where s.student.id in :studentIds
            group by s.student.id
            """)
    List<Object[]> lastActivityByStudent(@Param("studentIds") List<UUID> studentIds);

    @Query("""
            select count(session) as totalSessions,
                   sum(case when session.acceptedCorrection = true then 1 else 0 end) as acceptedSessions,
                   sum(case when session.acceptedCorrection = false then 1 else 0 end) as rejectedSessions,
                   sum(case when session.acceptedCorrection is null then 1 else 0 end) as unansweredSessions,
                   sum(case when session.wasEdited = true then 1 else 0 end) as editedSessions
            from CorrectionSession session
            where session.student.id = :studentId
              and session.createdAt >= :start
              and session.createdAt < :end
            """)
    AcceptanceSummaryProjection acceptanceSummary(
            @Param("studentId") UUID studentId,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
