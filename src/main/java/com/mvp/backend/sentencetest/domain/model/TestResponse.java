package com.mvp.backend.sentencetest.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_responses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_response_attempt_position", columnNames = {"attempt_id", "position"}),
        @UniqueConstraint(name = "uk_response_completion_key", columnNames = {"completion_key"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestResponse {

    /** Contadores de sugerencias reportados por el teclado al terminar la oracion. */
    public record Counters(int offered, int accepted, int rejected, int undone) {
        public static final Counters ZERO = new Counters(0, 0, 0, 0);
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private TestAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sentence_id", nullable = false)
    private TestSentence sentence;

    @Column(nullable = false)
    private int position;

    @Column(name = "pressed_start_at", nullable = false)
    private Instant pressedStartAt;

    @Column(name = "first_key_at")
    private Instant firstKeyAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_from_first_key_ms")
    private Long durationFromFirstKeyMs;

    @Column(name = "duration_from_start_ms")
    private Long durationFromStartMs;

    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(nullable = false)
    private boolean skipped;

    @Column(name = "suggestions_offered", nullable = false)
    private int suggestionsOffered;

    @Column(name = "suggestions_accepted", nullable = false)
    private int suggestionsAccepted;

    @Column(name = "suggestions_rejected", nullable = false)
    private int suggestionsRejected;

    @Column(name = "suggestions_undone", nullable = false)
    private int suggestionsUndone;

    @Column(name = "auto_error_count")
    private Integer autoErrorCount;

    @Column(name = "auto_error_detail", columnDefinition = "TEXT")
    private String autoErrorDetail;

    @Column(name = "annotated_error_count")
    private Integer annotatedErrorCount;

    @Column(name = "annotated_by")
    private UUID annotatedBy;

    @Column(name = "annotated_at")
    private Instant annotatedAt;

    @Column(name = "completion_key")
    private UUID completionKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Se crea al tocar Comenzar. */
    public TestResponse(TestAttempt attempt, TestSentence sentence, Instant pressedStartAt) {
        this.id = UUID.randomUUID();
        this.attempt = attempt;
        this.sentence = sentence;
        this.position = sentence.getPosition();
        this.pressedStartAt = pressedStartAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isStarted() {
        return pressedStartAt != null;
    }

    public boolean isFinished() {
        return finishedAt != null;
    }

    public boolean acceptsCorrections() {
        return isStarted() && !isFinished() && sentence.isAssisted();
    }

    /**
     * Terminar. Los offsets vienen del reloj monotonico del telefono relativos a Comenzar.
     * Texto no vacio sin primera tecla, o terminar antes de la primera tecla, es incoherente.
     */
    public void finish(String text, Long firstKeyOffsetMs, long finishedOffsetMs, boolean skipped, Counters counters,
            UUID completionKey, Instant now) {
        if (isFinished()) {
            throw new ConflictException("Sentence already finished");
        }
        String trimmed = text == null ? "" : text.strip();
        boolean empty = trimmed.isEmpty();
        if (!empty && firstKeyOffsetMs == null) {
            throw new BusinessException("firstKeyOffsetMs is required when there is text");
        }
        if (firstKeyOffsetMs != null && (firstKeyOffsetMs < 0 || finishedOffsetMs < firstKeyOffsetMs)) {
            throw new BusinessException("finishedOffsetMs must be after firstKeyOffsetMs");
        }
        if (finishedOffsetMs < 0) {
            throw new BusinessException("finishedOffsetMs must be positive");
        }
        this.finalText = trimmed;
        this.skipped = skipped || empty;
        this.durationFromStartMs = finishedOffsetMs;
        if (firstKeyOffsetMs != null) {
            this.firstKeyAt = pressedStartAt.plusMillis(firstKeyOffsetMs);
            long duration = finishedOffsetMs - firstKeyOffsetMs;
            this.durationFromFirstKeyMs = duration > 0 ? duration : null;
        }
        this.finishedAt = now;
        this.suggestionsOffered = counters.offered();
        this.suggestionsAccepted = counters.accepted();
        this.suggestionsRejected = counters.rejected();
        this.suggestionsUndone = counters.undone();
        this.completionKey = completionKey;
    }

    public boolean matchesCompletionKey(UUID key) {
        return completionKey != null && completionKey.equals(key);
    }

    /** Conteo automatico al terminar; conserva las incidencias registradas mientras la oracion estaba abierta. */
    public void recordAutoErrors(int count, String detailJson) {
        this.autoErrorCount = count;
        this.autoErrorDetail = AutoErrorDetail.merge(detailJson, this.autoErrorDetail);
    }

    public void appendDetail(String detailJson) {
        this.autoErrorDetail = detailJson;
    }

    public void annotate(int errorCount, UUID researcherId, Instant now) {
        if (errorCount < 0) {
            throw new BusinessException("Annotated error count must be >= 0");
        }
        this.annotatedErrorCount = errorCount;
        this.annotatedBy = researcherId;
        this.annotatedAt = now;
    }

    /** Errores efectivos: automatico en dictado, anotado en libre; null si falta anotar. */
    public Integer effectiveErrorCount() {
        return sentence.getKind() == SentenceKind.DICTATED ? autoErrorCount : annotatedErrorCount;
    }
}
