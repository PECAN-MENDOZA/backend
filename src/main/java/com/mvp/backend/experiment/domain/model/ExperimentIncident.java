package com.mvp.backend.experiment.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
 * Una incidencia registrada sobre una ejecucion. El historial es solo de inserciones: ninguna incidencia
 * se reescribe ni se borra, de modo que la auditoria conserva todos los motivos y no solo el ultimo.
 * El motivo es siempre un codigo fijo; nunca contiene texto del alumno.
 */
@Entity
@Table(name = "experiment_incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExperimentIncident {

    public static final int MAX_REASON_LENGTH = 80;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private ExperimentRun run;

    @Column(nullable = false, length = MAX_REASON_LENGTH, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    ExperimentIncident(ExperimentRun run, String reason, Instant createdAt) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Incident reason must have between 1 and " + MAX_REASON_LENGTH + " characters");
        }
        this.id = UUID.randomUUID();
        this.run = Objects.requireNonNull(run);
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(createdAt);
    }
}
