package com.mvp.backend.sentencetest.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Fila del listado de pruebas del investigador. */
public record TestSummaryResponse(
        UUID id,
        String code,
        String title,
        String status,
        int sentenceCount,
        int assignedCount,
        int completedCount,
        Instant createdAt) {
}
