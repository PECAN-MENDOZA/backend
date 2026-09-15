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
 * Una importacion ADJUDICATED registra ademas las dos importaciones de evaluador vigentes sobre las
 * que se resolvio el consenso; si alguna se reemplaza, la adjudicacion deja de ser vigente.
 */
@Entity
@Table(
        name = "annotation_imports",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_ann_import_hash", columnNames = {"batch_id", "slot", "file_sha256"}),
            @UniqueConstraint(name = "uk_ann_import_version", columnNames = {"batch_id", "slot", "version"})
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

    @Column(name = "based_on_rater1_import_id", updatable = false)
    private UUID basedOnRater1ImportId;

    @Column(name = "based_on_rater2_import_id", updatable = false)
    private UUID basedOnRater2ImportId;

    public AnnotationImport(
            AnnotationBatch batch,
            AnnotationSlot slot,
            int version,
            String rater,
            String fileSha256,
            String content,
            Researcher importedBy,
            Instant importedAt) {
        this(batch, slot, version, rater, fileSha256, content, importedBy, importedAt, null, null);
    }

    /** Importacion de consenso ligada a las dos importaciones de evaluador vigentes que resuelve. */
    public static AnnotationImport adjudicated(
            AnnotationBatch batch,
            int version,
            String rater,
            String fileSha256,
            String content,
            Researcher importedBy,
            Instant importedAt,
            AnnotationImport rater1Import,
            AnnotationImport rater2Import) {
        return new AnnotationImport(batch, AnnotationSlot.ADJUDICATED, version, rater, fileSha256, content,
                importedBy, importedAt, Objects.requireNonNull(rater1Import), Objects.requireNonNull(rater2Import));
    }

    private AnnotationImport(
            AnnotationBatch batch,
            AnnotationSlot slot,
            int version,
            String rater,
            String fileSha256,
            String content,
            Researcher importedBy,
            Instant importedAt,
            AnnotationImport rater1Import,
            AnnotationImport rater2Import) {
        if (version < 1) {
            throw new IllegalArgumentException("Import version starts at 1");
        }
        if ((slot == AnnotationSlot.ADJUDICATED) != (rater1Import != null && rater2Import != null)) {
            throw new IllegalArgumentException("Only an adjudicated import references the two rater imports");
        }
        if (rater1Import != null && (rater1Import.getSlot() != AnnotationSlot.RATER_1
                || rater2Import.getSlot() != AnnotationSlot.RATER_2
                || !rater1Import.isCurrent() || !rater2Import.isCurrent())) {
            throw new IllegalArgumentException("Adjudication must reference the current RATER_1 and RATER_2 imports");
        }
        this.id = UUID.randomUUID();
        this.basedOnRater1ImportId = rater1Import == null ? null : rater1Import.getId();
        this.basedOnRater2ImportId = rater2Import == null ? null : rater2Import.getId();
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

    /** Vigente y resuelta exactamente sobre las importaciones de evaluador indicadas. */
    public boolean adjudicates(AnnotationImport rater1Import, AnnotationImport rater2Import) {
        return slot == AnnotationSlot.ADJUDICATED && isCurrent()
                && rater1Import != null && rater1Import.isCurrent() && rater1Import.getId().equals(basedOnRater1ImportId)
                && rater2Import != null && rater2Import.isCurrent() && rater2Import.getId().equals(basedOnRater2ImportId);
    }

    /** Una importacion nueva de la misma ranura reemplaza a esta sin eliminarla. Idempotente. */
    public void supersede(Instant now) {
        if (supersededAt == null) {
            supersededAt = Objects.requireNonNull(now);
        }
    }
}
