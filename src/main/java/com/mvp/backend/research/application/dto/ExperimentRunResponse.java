package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.research.domain.model.TaskVariant;

/**
 * Resumen de una ejecucion para el investigador: metadatos y estado, sin el texto producido
 * (la anotacion humana es ciega y se exporta por lotes) ni la identidad del alumno.
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
                run.isExcluded(),
                run.getExcludedAt(),
                run.getExclusionReason(),
                run.getAppVersion(),
                run.getBackendVersion(),
                run.getModelVersion(),
                run.getCreatedAt());
    }
}
