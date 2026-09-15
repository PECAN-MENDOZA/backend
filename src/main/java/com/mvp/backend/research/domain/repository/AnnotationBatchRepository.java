package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.AnnotationBatch;

public interface AnnotationBatchRepository extends JpaRepository<AnnotationBatch, UUID> {

    Optional<AnnotationBatch> findByIdAndStudyId(UUID batchId, UUID studyId);

    List<AnnotationBatch> findByStudyIdOrderByCreatedAtDesc(UUID studyId);
}
