package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.research.domain.model.ParticipantSequence;

/**
 * Vista pseudonima del participante: nunca incluye la cuenta de alumno vinculada.
 * {@code nextSession} es {@code null} cuando ya completo ambas condiciones.
 */
public record ParticipantResponse(
        UUID id,
        String pseudonym,
        ParticipantSequence sequence,
        long completedRuns,
        boolean protocolCompleted,
        boolean hasOpenRun,
        NextSessionResponse nextSession,
        Instant createdAt) {
}
