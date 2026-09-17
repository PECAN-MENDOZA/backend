package com.mvp.backend.sentencetest.domain.model;

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

@Entity
@Table(name = "test_sentences", uniqueConstraints = @UniqueConstraint(name = "uk_test_sentence_position", columnNames = {"test_id", "position"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestSentence {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_id", nullable = false)
    private SentenceTest test;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SentenceKind kind;

    @Column(name = "reference_text", nullable = false, columnDefinition = "TEXT")
    private String referenceText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Assistance assistance;

    public TestSentence(SentenceTest test, int position, SentenceKind kind, String referenceText, Assistance assistance) {
        this.id = UUID.randomUUID();
        this.test = test;
        this.position = position;
        this.kind = kind;
        this.referenceText = referenceText;
        this.assistance = assistance;
    }

    public boolean isAssisted() {
        return assistance == Assistance.ASSISTED;
    }
}
