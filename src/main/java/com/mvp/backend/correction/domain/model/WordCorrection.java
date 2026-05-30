package com.mvp.backend.correction.domain.model;

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

@Entity
@Table(name = "word_corrections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WordCorrection {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "correction_session_id", nullable = false)
    private CorrectionSession correctionSession;

    @Column(name = "original_word", nullable = false, length = 160)
    private String originalWord;

    @Column(name = "corrected_word", nullable = false, length = 160)
    private String correctedWord;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 30)
    private ErrorType errorType;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "start_position", nullable = false)
    private int startPosition;

    @Column(name = "end_position", nullable = false)
    private int endPosition;

    public WordCorrection(
            CorrectionSession correctionSession,
            String originalWord,
            String correctedWord,
            ErrorType errorType,
            double confidence,
            int startPosition,
            int endPosition) {
        this.id = UUID.randomUUID();
        this.correctionSession = correctionSession;
        this.originalWord = originalWord;
        this.correctedWord = correctedWord;
        this.errorType = errorType;
        this.confidence = confidence;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }
}
