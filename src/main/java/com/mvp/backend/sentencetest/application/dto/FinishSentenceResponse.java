package com.mvp.backend.sentencetest.application.dto;

import java.util.UUID;

/** nextPosition es null cuando el intento ya no esta en curso (completado o cancelado). */
public record FinishSentenceResponse(UUID responseId, Integer nextPosition, String attemptStatus) {
}
