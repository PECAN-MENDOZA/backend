package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.research.domain.model.TaskVariant;

/** El codigo en claro se entrega una sola vez; el servidor solo conserva su hash. */
public record AccessCodeResponse(
        UUID runId,
        UUID participantId,
        String pseudonym,
        String code,
        Instant expiresAt,
        TaskVariant task,
        ExperimentCondition condition) {
}
