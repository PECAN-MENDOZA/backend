package com.mvp.backend.sentencetest.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.sentencetest.domain.model.SentenceTest;

public interface SentenceTestRepository extends JpaRepository<SentenceTest, UUID> {
    boolean existsByCode(String code);
    Optional<SentenceTest> findByCode(String code);
    List<SentenceTest> findAllByOrderByCreatedAtDesc();
}
