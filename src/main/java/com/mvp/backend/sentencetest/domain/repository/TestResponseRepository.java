package com.mvp.backend.sentencetest.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.sentencetest.domain.model.TestResponse;

public interface TestResponseRepository extends JpaRepository<TestResponse, UUID> {
    List<TestResponse> findByAttemptIdOrderByPositionAsc(UUID attemptId);
    Optional<TestResponse> findByAttemptIdAndPosition(UUID attemptId, int position);
    long countByAttemptIdAndFinishedAtIsNotNull(UUID attemptId);
    List<TestResponse> findByAttemptIdInOrderByAttemptIdAscPositionAsc(List<UUID> attemptIds);
}
