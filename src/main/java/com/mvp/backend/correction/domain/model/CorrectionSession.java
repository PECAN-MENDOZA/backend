package com.mvp.backend.correction.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.student.domain.model.Student;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "correction_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CorrectionSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "corrected_text", columnDefinition = "TEXT")
    private String correctedText;

    @Column(name = "corrections_count", nullable = false)
    private int correctionsCount;

    @Column(name = "suggestions_json", columnDefinition = "TEXT")
    private String suggestionsJson;

    @Column(name = "selected_suggestion", columnDefinition = "TEXT")
    private String selectedSuggestion;

    @Column(name = "accepted_correction")
    private Boolean acceptedCorrection;

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "was_edited", nullable = false)
    private boolean wasEdited;

    @Column(name = "feedback_reason", length = 40)
    private String feedbackReason;

    // Solo cuando la correccion se pidio dentro de una ejecucion experimental (uso normal: null).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_run_id")
    private ExperimentRun experimentRun;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CorrectionSession(Student student, String originalText) {
        this(student, originalText, null);
    }

    public CorrectionSession(Student student, String originalText, ExperimentRun experimentRun) {
        this.id = UUID.randomUUID();
        this.student = student;
        this.originalText = originalText;
        this.experimentRun = experimentRun;
    }

    public boolean isExperimental() {
        return experimentRun != null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void complete(
            String correctedText,
            int correctionsCount,
            String suggestionsJson,
            Long responseTimeMs) {
        this.correctedText = correctedText;
        this.correctionsCount = correctionsCount;
        this.suggestionsJson = suggestionsJson;
        this.responseTimeMs = responseTimeMs;
    }

    public void registerFeedback(
            String selectedSuggestion,
            String finalText,
            boolean acceptedCorrection,
            int correctionsCount,
            String feedbackReason) {
        this.selectedSuggestion = selectedSuggestion;
        this.finalText = finalText;
        // Solo cuenta como edicion si la correccion quedo efectivamente aceptada: tras un UNDO con
        // texto_final el alumno no valido nada, asi que wasEdited no debe inflar la tasa de edicion.
        this.wasEdited = acceptedCorrection && finalText != null && !finalText.equals(selectedSuggestion);
        this.acceptedCorrection = acceptedCorrection;
        this.correctionsCount = correctionsCount;
        this.feedbackReason = feedbackReason;
    }
}
