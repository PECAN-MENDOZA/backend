package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentIncident;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.research.domain.model.TaskVariant;

/**
 * Resumen de una ejecucion para el investigador: metadatos, estado e historial de incidencias, sin el
 * texto producido (la anotacion humana es ciega y se exporta por lotes) ni la identidad del alumno.
 */
public record ExperimentRunResponse(
        UUID id,
        UUID participantId,
        String pseudonym,
        TaskVariant task,
        ExperimentCondition condition,
        ExperimentRunStatus status,
        Instant accessCodeExpiresAt,
        Instant redeemedAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        int incidentCount,
        String failureReason,
        List<IncidentResponse> incidents,
        boolean excluded,
        Instant excludedAt,
        String exclusionReason,
        String appVersion,
        String backendVersion,
        String modelVersion,
        Instant createdAt) {

    public static ExperimentRunResponse from(ExperimentRun run) {
        return new ExperimentRunResponse(
                run.getId(),
                run.getParticipant().getId(),
                run.getParticipant().getPseudonym(),
                run.getTask().getVariant(),
                run.getCondition(),
                run.getStatus(),
                run.getAccessCodeExpiresAt(),
                run.getRedeemedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getDurationMs(),
                run.getIncidentCount(),
                run.getFailureReason(),
                run.getIncidents().stream().map(IncidentResponse::from).toList(),
                run.isExcluded(),
                run.getExcludedAt(),
                run.getExclusionReason(),
                run.getAppVersion(),
                run.getBackendVersion(),
                run.getModelVersion(),
                run.getCreatedAt());
    }

    /** Una entrada del historial de incidencias: codigo fijo del motivo e instante en que se registro. */
    public record IncidentResponse(String reason, Instant at) {

        static IncidentResponse from(ExperimentIncident incident) {
            return new IncidentResponse(incident.getReason(), incident.getCreatedAt());
        }
    }
}
