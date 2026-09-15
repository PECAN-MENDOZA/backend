package com.mvp.backend.experiment.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.research.domain.model.TaskVariant;

/**
 * Asignacion inmutable que ve el alumno: consigna y condicion fijadas por el investigador, mas el
 * estado para restaurar la pantalla correcta. Nunca contiene datos de otros participantes, el hash
 * del codigo ni el texto final.
 */
public record ExperimentRunResponse(
        UUID id,
        String participantCode,
        ExperimentCondition condition,
        TaskVariant taskVariant,
        String promptText,
        String status,
        Instant startedAt,
        Instant expiresAt) {

    public static ExperimentRunResponse from(ExperimentRun run) {
        return new ExperimentRunResponse(
                run.getId(),
                run.getParticipant().getPseudonym(),
                run.getCondition(),
                run.getTask().getVariant(),
                run.getTask().getPromptText(),
                run.getStatus().name(),
                run.getStartedAt(),
                run.getAccessCodeExpiresAt());
    }
}
