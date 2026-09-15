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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Archivo importado para una ranura del lote. Conserva autor, fecha, hash, version y el contenido
 * integro; una sustitucion posterior lo marca como superado ({@code supersededAt}) y nunca lo borra.
 */
@Entity
@Table(
        name = "annotation_imports",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_annotation_import", columnNames = {"batch_id", "slot", "file_sha256"}),
            @UniqueConstraint(name = "uk_annotation_import_version", columnNames = {"batch_id", "slot", "version"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnnotationImport {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private AnnotationBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AnnotationSlot slot;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, length = 80, updatable = false)
    private String rater;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "imported_by", nullable = false, updatable = false)
    private Researcher importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    public AnnotationImport(
            AnnotationBatch batch,
            AnnotationSlot slot,
            int version,
            String rater,
            String fileSha256,
            String content,
            Researcher importedBy,
            Instant importedAt) {
        if (version < 1) {
            throw new IllegalArgumentException("Import version starts at 1");
        }
        this.id = UUID.randomUUID();
        this.batch = Objects.requireNonNull(batch);
        this.slot = Objects.requireNonNull(slot);
        this.version = version;
        this.rater = Objects.requireNonNull(rater);
        this.fileSha256 = Objects.requireNonNull(fileSha256);
        this.content = Objects.requireNonNull(content);
        this.importedBy = Objects.requireNonNull(importedBy);
        this.importedAt = Objects.requireNonNull(importedAt);
    }

    public boolean isCurrent() {
        return supersededAt == null;
    }

    /** Una importacion nueva de la misma ranura reemplaza a esta sin eliminarla. Idempotente. */
    public void supersede(Instant now) {
        if (supersededAt == null) {
            supersededAt = Objects.requireNonNull(now);
        }
    }
}
