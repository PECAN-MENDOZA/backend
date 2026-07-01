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

    void deleteByCorrectionSessionId(UUID correctionSessionId);

    @Query("""
            select correction.originalWord,
                   count(correction),
                   sum(case when session.acceptedCorrection = true then 1 else 0 end)
            from WordCorrection correction
            join correction.correctionSession session
            where session.student.id = :studentId
              and session.createdAt >= :start
              and session.createdAt < :end
            group by correction.originalWord
            order by count(correction) desc
            """)
    List<Object[]> topWords(
            @Param("studentId") UUID studentId,
            @Param("start") Instant start,
            @Param("end") Instant end,
            Pageable pageable);

    @Query("""
            select correction.originalWord, correction.correctedWord
            from WordCorrection correction
            join correction.correctionSession session
            where session.student.id = :studentId
              and session.createdAt >= :start
              and session.createdAt < :end
            """)
    List<Object[]> wordPairsForMonth(
            @Param("studentId") UUID studentId,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
