package com.mvp.backend.sentencetest.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Detalle de una prueba: resumen, notas, oraciones en orden y conteos por tipo y ayuda. */
public record TestDetailResponse(
        UUID id,
        String code,
        String title,
        String status,
        int sentenceCount,
        int assignedCount,
        int completedCount,
        Instant createdAt,
        String notes,
        List<SentenceEntry> sentences,
        Counts counts) {

    public record SentenceEntry(int position, String kind, String referenceText, String assistance) {
    }

    public record Counts(int dictated, int free, int assisted, int unassisted) {
    }
}
