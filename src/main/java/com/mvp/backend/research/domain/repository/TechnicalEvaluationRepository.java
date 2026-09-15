package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.TechnicalEvaluation;

public interface TechnicalEvaluationRepository extends JpaRepository<TechnicalEvaluation, UUID> {

    List<TechnicalEvaluation> findByCreatedByIdOrderByCreatedAtDesc(UUID researcherId);

    List<TechnicalEvaluation> findByCreatedByIdAndModelVersionOrderByCreatedAtDesc(UUID researcherId, String modelVersion);

    Optional<TechnicalEvaluation> findFirstByCreatedByIdOrderByCreatedAtDesc(UUID researcherId);

    Optional<TechnicalEvaluation> findFirstByCreatedByIdAndModelVersionOrderByCreatedAtDesc(UUID researcherId, String modelVersion);
}
