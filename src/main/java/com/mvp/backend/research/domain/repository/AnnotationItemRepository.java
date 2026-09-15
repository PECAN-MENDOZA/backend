package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.AnnotationItem;

public interface AnnotationItemRepository extends JpaRepository<AnnotationItem, UUID> {

    List<AnnotationItem> findByBatchIdOrderByPositionAsc(UUID batchId);
}
