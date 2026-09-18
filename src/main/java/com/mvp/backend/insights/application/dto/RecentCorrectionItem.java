package com.mvp.backend.insights.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Una correccion pedida por un alumno del salon, con su desenlace y el contexto de prueba si lo hubo. */
public record RecentCorrectionItem(
        UUID sessionId,
        UUID studentId,
        String username,
        String realName,
        Instant createdAt,
        String originalText,
        String correctedText,
        String finalText,
        String outcome,
        String outcomeLabel,
        boolean inTest,
        String assistance) {
}
