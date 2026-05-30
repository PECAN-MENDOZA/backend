package com.mvp.backend.correction.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.correction.domain.model.CorrectionSession;

public interface CorrectionSessionRepository extends JpaRepository<CorrectionSession, UUID> {

    interface AcceptanceSummaryProjection {
        Long getTotalSessions();

        Long getAcceptedSessions();

        Long getRejectedSessions();

        Long getUnansweredSessions();
    }

    Optional<CorrectionSession> findByIdAndStudentId(UUID id, UUID studentId);

    Page<CorrectionSession> findByStudentIdOrderByCreatedAtDesc(UUID studentId, Pageable pageable);

    @Query("""
            select count(session) as totalSessions,
                   sum(case when session.acceptedCorrection = true then 1 else 0 end) as acceptedSessions,
                   sum(case when session.acceptedCorrection = false then 1 else 0 end) as rejectedSessions,
                   sum(case when session.acceptedCorrection is null then 1 else 0 end) as unansweredSessions
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
