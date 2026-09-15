package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationSlot;

public interface AnnotationImportRepository extends JpaRepository<AnnotationImport, UUID> {

    List<AnnotationImport> findByBatchIdOrderByVersionAsc(UUID batchId);

    Optional<AnnotationImport> findFirstByBatchIdAndSlotAndSupersededAtIsNull(UUID batchId, AnnotationSlot slot);

    Optional<AnnotationImport> findFirstByBatchIdAndSlotOrderByVersionDesc(UUID batchId, AnnotationSlot slot);

    boolean existsByBatchIdAndSlotAndFileSha256(UUID batchId, AnnotationSlot slot, String fileSha256);
}
