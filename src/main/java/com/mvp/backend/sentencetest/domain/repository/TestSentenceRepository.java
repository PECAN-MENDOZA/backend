package com.mvp.backend.sentencetest.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.sentencetest.domain.model.TestSentence;

public interface TestSentenceRepository extends JpaRepository<TestSentence, UUID> {
    List<TestSentence> findByTestIdOrderByPositionAsc(UUID testId);
    Optional<TestSentence> findByTestIdAndPosition(UUID testId, int position);
    long countByTestId(UUID testId);
    void deleteByTestId(UUID testId);
}
