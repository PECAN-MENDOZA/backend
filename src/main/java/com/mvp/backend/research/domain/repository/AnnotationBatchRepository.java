package com.mvp.backend.research.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.research.domain.model.AnnotationBatch;

import jakarta.persistence.LockModeType;

public interface AnnotationBatchRepository extends JpaRepository<AnnotationBatch, UUID> {

    Optional<AnnotationBatch> findByIdAndStudyId(UUID batchId, UUID studyId);

    // Toda importacion toma el bloqueo de fila del lote: dos importaciones simultaneas (misma u otra
    // ranura) se serializan y la segunda relee el estado ya confirmado (evaluador vigente, version,
    // adjudicacion) en vez de decidir sobre una copia obsoleta.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AnnotationBatch b where b.id = :id and b.study.id = :studyId")
    Optional<AnnotationBatch> findByIdAndStudyIdForUpdate(@Param("id") UUID id, @Param("studyId") UUID studyId);

    List<AnnotationBatch> findByStudyIdOrderByCreatedAtDesc(UUID studyId);
}
