package com.mvp.backend.research.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lote ciego de anotacion. Se congela al crearse: el numero de filas y el SHA-256 del CSV exportado
 * permiten reproducir y verificar la exportacion byte a byte.
 */
@Entity
@Table(name = "annotation_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnnotationBatch {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_id", nullable = false, updatable = false)
    private ResearchStudy study;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AnnotationKind kind;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "export_sha256", nullable = false, length = 64)
    private String exportSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private Researcher createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AnnotationBatch(ResearchStudy study, AnnotationKind kind, Researcher createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.study = Objects.requireNonNull(study);
        this.kind = Objects.requireNonNull(kind);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** Fija el contenido exportado; solo una vez, antes de persistir. */
    public void freeze(int rowCount, String exportSha256) {
        if (this.exportSha256 != null) {
            throw new IllegalStateException("Batch is already frozen");
        }
        if (rowCount <= 0 || exportSha256 == null || exportSha256.length() != 64) {
            throw new IllegalArgumentException("Invalid batch content");
        }
        this.rowCount = rowCount;
        this.exportSha256 = exportSha256;
    }
}
