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

    @Query(value = """
            select to_char(date_trunc('month', created_at at time zone 'UTC'), 'YYYY-MM') as month,
                   count(*) as total,
                   coalesce(sum(case when accepted_correction = true then 1 else 0 end), 0) as accepted
            from correction_sessions
            where student_id = :studentId
              and created_at >= :start
              and created_at < :end
            group by month
            order by month
            """, nativeQuery = true)
    List<Object[]> monthlyAcceptance(
            @Param("studentId") UUID studentId,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
