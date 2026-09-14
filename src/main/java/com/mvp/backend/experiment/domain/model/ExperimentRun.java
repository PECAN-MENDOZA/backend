package com.mvp.backend.experiment.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.research.domain.model.ProtocolTask;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;

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

/**
 * Ejecucion de una tarea bajo una condicion fija. La condicion y la tarea se asignan al crear la
 * ejecucion y son inmutables; el ciclo de vida es PENDING -> ACTIVE -> COMPLETED con salidas
 * CANCELLED / EXPIRED / TECHNICAL_FAILURE.
 */
@Entity
@Table(name = "experiment_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExperimentRun {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false, updatable = false)
    private StudyParticipant participant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "protocol_id", nullable = false, updatable = false)
    private StudyProtocol protocol;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private ProtocolTask task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ExperimentCondition condition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExperimentRunStatus status;

    @Column(name = "access_code_hash", length = 64)
    private String accessCodeHash;

    @Column(name = "access_code_expires_at")
    private Instant accessCodeExpiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "completion_key", unique = true)
    private UUID completionKey;

    @Column(name = "app_version", length = 80)
    private String appVersion;

    @Column(name = "backend_version", length = 80)
    private String backendVersion;

    @Column(name = "model_version", length = 160)
    private String modelVersion;

    @Column(name = "incident_count", nullable = false)
    private int incidentCount;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "excluded_at")
    private Instant excludedAt;

    @Column(name = "exclusion_reason", length = 500)
    private String exclusionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "excluded_by")
    private Researcher excludedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ExperimentRun(
            StudyParticipant participant,
            StudyProtocol protocol,
            ProtocolTask task,
            ExperimentCondition condition,
            String accessCodeHash,
            Instant expiresAt,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.participant = participant;
        this.protocol = protocol;
        this.task = task;
        this.condition = condition;
        this.status = ExperimentRunStatus.PENDING;
        this.accessCodeHash = accessCodeHash;
        this.accessCodeExpiresAt = expiresAt;
        this.incidentCount = 0;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void redeem(Instant now) {
        if (status != ExperimentRunStatus.PENDING || accessCodeExpiresAt.isBefore(now)) {
            throw new IllegalStateException("Run cannot be redeemed");
        }
        // El hash se conserva hasta start(): si el telefono se cierra en la pantalla de
        // confirmacion, el mismo alumno puede volver a canjear el codigo (idempotente) o
        // recuperar la ejecucion por /runs/active. Un segundo canje no cambia redeemedAt.
        if (redeemedAt == null) {
            redeemedAt = now;
        }
    }

    public boolean isRedeemedPending() {
        return status == ExperimentRunStatus.PENDING && redeemedAt != null;
    }

    public void start(Instant now) {
        if (status == ExperimentRunStatus.ACTIVE) {
            return; // idempotente
        }
        if (status != ExperimentRunStatus.PENDING || redeemedAt == null) {
            throw new IllegalStateException("Run is not ready to start");
        }
        status = ExperimentRunStatus.ACTIVE;
        startedAt = now;
        accessCodeHash = null;
    }

    public void complete(String text, long duration, UUID key, Instant now) {
        if (status == ExperimentRunStatus.COMPLETED && key.equals(completionKey)) {
            return;
        }
        if (status != ExperimentRunStatus.ACTIVE) {
            throw new IllegalStateException("Run is not active");
        }
        if (text == null || text.isBlank() || duration <= 0) {
            throw new IllegalArgumentException("Invalid completion");
        }
        finalText = text;
        durationMs = duration;
        completionKey = key;
        completedAt = now;
        status = ExperimentRunStatus.COMPLETED;
        accessCodeHash = null;
    }

    /** Registra una incidencia sin cambiar el estado ni el texto (auditoria, nunca correccion silenciosa). */
    public void recordIncident(String reason) {
        incidentCount++;
        failureReason = reason;
    }

    public void recordVersions(String appVersion, String backendVersion, String modelVersion) {
        this.appVersion = appVersion;
        this.backendVersion = backendVersion;
        this.modelVersion = modelVersion;
    }

    public boolean isActive() {
        return status == ExperimentRunStatus.ACTIVE;
    }

    public boolean isCompleted() {
        return status == ExperimentRunStatus.COMPLETED;
    }

    public boolean isExcluded() {
        return excludedAt != null;
    }
}
