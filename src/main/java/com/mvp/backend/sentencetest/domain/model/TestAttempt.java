package com.mvp.backend.sentencetest.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.student.domain.model.Student;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "test_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestAttempt {

    public static final int EXCLUSION_REASON_MIN = 10;
    public static final int EXCLUSION_REASON_MAX = 500;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_id", nullable = false)
    private SentenceTest test;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private AttemptCancelReason cancelReason;

    @Column(name = "app_version", length = 80)
    private String appVersion;

    @Column(name = "backend_version", length = 80)
    private String backendVersion;

    @Column(name = "model_version", length = 160)
    private String modelVersion;

    @Column(name = "incident_count", nullable = false)
    private int incidentCount;

    @Column(name = "excluded_at")
    private Instant excludedAt;

    @Column(name = "exclusion_reason", length = 500)
    private String exclusionReason;

    @Column(name = "excluded_by")
    private UUID excludedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TestAttempt(SentenceTest test, Student student, String appVersion, String backendVersion, Instant now) {
        this.id = UUID.randomUUID();
        this.test = test;
        this.student = student;
        this.status = AttemptStatus.IN_PROGRESS;
        this.startedAt = now;
        this.appVersion = appVersion;
        this.backendVersion = backendVersion;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isInProgress() {
        return status == AttemptStatus.IN_PROGRESS;
    }

    public boolean isCompleted() {
        return status == AttemptStatus.COMPLETED;
    }

    public boolean isExcluded() {
        return excludedAt != null;
    }

    public void requireInProgress() {
        if (!isInProgress()) {
            throw new ConflictException("Attempt is not in progress");
        }
    }

    /**
     * Congela la version del modelo con la primera correccion asistida. Devuelve false si una
     * correccion posterior trae otra version (el llamador registra la incidencia).
     */
    public boolean recordModelVersion(String version) {
        if (version == null) {
            return true;
        }
        if (modelVersion == null) {
            modelVersion = version;
            return true;
        }
        return modelVersion.equals(version);
    }

    public void recordIncident() {
        incidentCount++;
    }

    public void complete(Instant now) {
        requireInProgress();
        this.status = AttemptStatus.COMPLETED;
        this.completedAt = now;
    }

    public void cancel(AttemptCancelReason reason, Instant now) {
        requireInProgress();
        this.status = AttemptStatus.CANCELLED;
        this.cancelReason = reason;
        this.completedAt = now;
    }

    public void exclude(String reason, UUID researcherId, Instant now) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < EXCLUSION_REASON_MIN || trimmed.length() > EXCLUSION_REASON_MAX) {
            throw new BusinessException("Exclusion reason must have between 10 and 500 characters");
        }
        this.excludedAt = now;
        this.exclusionReason = trimmed;
        this.excludedBy = researcherId;
    }
}
