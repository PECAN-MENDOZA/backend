package com.mvp.backend.correction.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.correction.domain.model.WordCorrection;

public interface WordCorrectionRepository extends JpaRepository<WordCorrection, UUID> {

    List<WordCorrection> findByCorrectionSessionIdOrderByStartPosition(UUID correctionSessionId);

    @Query("""
            select correction.errorType, count(correction)
            from WordCorrection correction
            join correction.correctionSession session
            where session.student.id = :studentId
              and session.createdAt >= :start
              and session.createdAt < :end
              and correction.errorType <> com.mvp.backend.correction.domain.model.ErrorType.NONE
            group by correction.errorType
            order by count(correction) desc
            """)
    List<Object[]> errorDistribution(@Param("studentId") UUID studentId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select correction.originalWord,
                   correction.errorType,
                   count(correction),
                   avg(correction.confidence),
                   sum(case when session.acceptedCorrection = true then 1 else 0 end)
            from WordCorrection correction
            join correction.correctionSession session
            where session.student.id = :studentId
              and session.createdAt >= :start
              and session.createdAt < :end
              and correction.errorType <> com.mvp.backend.correction.domain.model.ErrorType.NONE
            group by correction.originalWord, correction.errorType
            order by count(correction) desc
            """)
    List<Object[]> topWords(
            @Param("studentId") UUID studentId,
            @Param("start") Instant start,
            @Param("end") Instant end,
            Pageable pageable);
}
