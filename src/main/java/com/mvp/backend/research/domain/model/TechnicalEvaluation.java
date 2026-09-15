package com.mvp.backend.research.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Evaluacion tecnica independiente del modelo (spec §11.4, criterios §6): Precision, Recall y F0.5 sobre
 * un conjunto reservado identificado por su SHA-256 y un scorer versionado. Es inmutable y nunca se
 * combina con los resultados intra-sujeto: no referencia estudios, participantes ni ejecuciones.
 */
@Entity
@Table(name = "technical_evaluations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TechnicalEvaluation {

    public static final String SCORER_VERSION = "exact_token_edits_v1";
    /** Tolerancia entre los valores informados (P, R, F0.5) y los recalculados a partir de sus estadisticos. */
    public static final double F_TOLERANCE = 1e-6;
    public static final String COUNTS_MISMATCH = "Precision/recall do not match TP/FP/FN";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Id
    private UUID id;

    @Column(name = "model_version", nullable = false, length = 160, updatable = false)
    private String modelVersion;

    @Column(name = "dataset_sha256", nullable = false, length = 64, updatable = false)
    private String datasetSha256;

    @Column(name = "scorer_version", nullable = false, length = 80, updatable = false)
    private String scorerVersion;

    @Column(name = "precision_value", nullable = false, updatable = false)
    private double precisionValue;

    @Column(name = "recall_value", nullable = false, updatable = false)
    private double recallValue;

    @Column(name = "f_zero_five", nullable = false, updatable = false)
    private double fZeroFive;

    @Column(name = "true_positives", nullable = false, updatable = false)
    private int truePositives;

    @Column(name = "false_positives", nullable = false, updatable = false)
    private int falsePositives;

    @Column(name = "false_negatives", nullable = false, updatable = false)
    private int falseNegatives;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private Researcher createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TechnicalEvaluation(
            String modelVersion,
            String datasetSha256,
            String scorerVersion,
            double precision,
            double recall,
            double fZeroFive,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            Researcher createdBy,
            Instant createdAt) {
        if (modelVersion == null || modelVersion.isBlank() || modelVersion.strip().length() > 160) {
            throw new IllegalArgumentException("Model version is required (at most 160 characters)");
        }
        if (datasetSha256 == null || !SHA256.matcher(datasetSha256).matches()) {
            throw new IllegalArgumentException("Dataset hash must be a lowercase hex SHA-256");
        }
        if (!SCORER_VERSION.equals(scorerVersion)) {
            throw new IllegalArgumentException("Scorer version must be " + SCORER_VERSION);
        }
        requireRate("Precision", precision);
        requireRate("Recall", recall);
        requireRate("F0.5", fZeroFive);
        if (truePositives < 0 || falsePositives < 0 || falseNegatives < 0) {
            throw new IllegalArgumentException("TP, FP and FN must be non-negative");
        }
        if (!countsConsistent(precision, recall, truePositives, falsePositives, falseNegatives)) {
            throw new IllegalArgumentException(COUNTS_MISMATCH);
        }
        if (Math.abs(fZeroFive(precision, recall) - fZeroFive) > F_TOLERANCE) {
            throw new IllegalArgumentException("F0.5 does not match precision and recall");
        }
        this.id = UUID.randomUUID();
        this.modelVersion = modelVersion.strip();
        this.datasetSha256 = datasetSha256;
        this.scorerVersion = scorerVersion;
        this.precisionValue = precision;
        this.recallValue = recall;
        this.fZeroFive = fZeroFive;
        this.truePositives = truePositives;
        this.falsePositives = falsePositives;
        this.falseNegatives = falseNegatives;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    private static void requireRate(String name, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    /**
     * P y R deben reproducirse desde los conteos: {@code P = TP / (TP + FP)} y {@code R = TP / (TP + FN)} dentro
     * de {@link #F_TOLERANCE}; con denominador 0 el valor informado debe ser exactamente 0 (convencion documentada).
     */
    public static boolean countsConsistent(double precision, double recall, int tp, int fp, int fn) {
        return matchesRatio(precision, tp, tp + fp) && matchesRatio(recall, tp, tp + fn);
    }

    private static boolean matchesRatio(double reported, int numerator, int denominator) {
        if (denominator == 0) {
            return reported == 0.0;
        }
        return Math.abs((double) numerator / denominator - reported) <= F_TOLERANCE;
    }

    /** {@code F0.5 = 1.25 * P * R / (0.25 * P + R)}; 0 cuando ambos son 0 (denominador nulo). */
    public static double fZeroFive(double precision, double recall) {
        double denominator = 0.25 * precision + recall;
        return denominator == 0.0 ? 0.0 : 1.25 * precision * recall / denominator;
    }

    /** F1 descriptivo (criterios §6.1); 0 cuando ambos son 0. */
    public static double fOne(double precision, double recall) {
        double denominator = precision + recall;
        return denominator == 0.0 ? 0.0 : 2.0 * precision * recall / denominator;
    }
}
