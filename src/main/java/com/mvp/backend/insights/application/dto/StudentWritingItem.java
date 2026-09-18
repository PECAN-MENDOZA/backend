package com.mvp.backend.insights.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Una escritura del alumno: lo que escribio, como quedo y que hizo con la ayuda; con la prueba si la hubo. */
public record StudentWritingItem(
        UUID sessionId,
        Instant createdAt,
        String originalText,
        String finalText,
        String outcome,
        String outcomeLabel,
        boolean inTest,
        String assistance,
        String testCode) {
}
