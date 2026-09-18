package com.mvp.backend.sentencetest.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.sentencetest.domain.model.TestResponse;

public interface TestResponseRepository extends JpaRepository<TestResponse, UUID> {
    List<TestResponse> findByAttemptIdOrderByPositionAsc(UUID attemptId);
    Optional<TestResponse> findByAttemptIdAndPosition(UUID attemptId, int position);
    long countByAttemptIdAndFinishedAtIsNotNull(UUID attemptId);
    List<TestResponse> findByAttemptIdInOrderByAttemptIdAscPositionAsc(List<UUID> attemptIds);

    // Respuestas de varios intentos con su oracion ya cargada (ficha del alumno en el panel docente).
    @Query("""
            select r from TestResponse r join fetch r.sentence
            where r.attempt.id in :attemptIds
            order by r.attempt.id asc, r.position asc
            """)
    List<TestResponse> findWithSentenceByAttemptIdIn(@Param("attemptIds") List<UUID> attemptIds);
}
