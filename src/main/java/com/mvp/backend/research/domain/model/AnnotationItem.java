package com.mvp.backend.research.domain.model;

import java.util.Objects;
import java.util.UUID;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.experiment.domain.model.ExperimentRun;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Fila de un lote: la unica correspondencia entre el codigo de muestra ciego y la ejecucion (y, en
 * lotes semanticos, la sesion de correccion y el indice de la sugerencia evaluada). Los puntajes de
 * cada ranura reflejan la ultima importacion vigente de esa ranura.
 */
@Entity
@Table(
        name = "annotation_items",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_annotation_sample_code", columnNames = "sample_code"),
            @UniqueConstraint(name = "uk_annotation_item_position", columnNames = {"batch_id", "position"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnnotationItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private AnnotationBatch batch;

    @Column(name = "sample_code", nullable = false, length = 24, updatable = false)
    private String sampleCode;

    @Column(nullable = false, updatable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private ExperimentRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "correction_session_id", updatable = false)
    private CorrectionSession correctionSession;

    @Column(name = "suggestion_index", updatable = false)
    private Integer suggestionIndex;

    @Column(name = "rater_1_score")
    private Integer rater1Score;

    @Column(name = "rater_2_score")
    private Integer rater2Score;

    @Column(name = "adjudicated_score")
    private Integer adjudicatedScore;

    public AnnotationItem(
            AnnotationBatch batch,
            String sampleCode,
            int position,
            ExperimentRun run,
            CorrectionSession correctionSession,
            Integer suggestionIndex) {
        if ((correctionSession == null) != (suggestionIndex == null) || (suggestionIndex != null && suggestionIndex < 0)) {
            throw new IllegalArgumentException("A semantic item needs a session and a suggestion index");
        }
        this.id = UUID.randomUUID();
        this.batch = Objects.requireNonNull(batch);
        this.sampleCode = Objects.requireNonNull(sampleCode);
        this.position = position;
        this.run = Objects.requireNonNull(run);
        this.correctionSession = correctionSession;
        this.suggestionIndex = suggestionIndex;
    }

    public Integer score(AnnotationSlot slot) {
        return switch (slot) {
            case RATER_1 -> rater1Score;
            case RATER_2 -> rater2Score;
            case ADJUDICATED -> adjudicatedScore;
        };
    }

    public void record(AnnotationSlot slot, int score) {
        if (score < 0) {
            throw new IllegalArgumentException("Score must be non-negative");
        }
        switch (slot) {
            case RATER_1 -> rater1Score = score;
            case RATER_2 -> rater2Score = score;
            case ADJUDICATED -> adjudicatedScore = score;
        }
    }
}
