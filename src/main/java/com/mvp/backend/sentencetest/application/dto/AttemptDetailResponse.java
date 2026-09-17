package com.mvp.backend.sentencetest.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Intento visto por el investigador: cabecera y una fila por oracion respondida. */
public record AttemptDetailResponse(
        UUID attemptId,
        String studentUsername,
        String status,
        Instant startedAt,
        Instant completedAt,
        String cancelReason,
        String appVersion,
        String backendVersion,
        String modelVersion,
        int incidentCount,
        Instant excludedAt,
        String exclusionReason,
        List<ResponseRow> responses) {

    /**
     * Fila por oracion. wordCount: dictadas sobre la referencia, libres sobre el texto final.
     * errorSource: AUTO (dictada), ANNOTATED (libre anotada) o PENDING (libre sin anotar).
     * autoErrorDetail es el JSON del alineador tal cual se guardo (cadena).
     */
    public record ResponseRow(
            UUID responseId,
            int position,
            String kind,
            String assistance,
            String referenceText,
            String finalText,
            boolean skipped,
            int wordCount,
            Integer autoErrorCount,
            String autoErrorDetail,
            Integer annotatedErrorCount,
            Integer effectiveErrorCount,
            String errorSource,
            Long durationFromFirstKeyMs,
            Long durationFromStartMs,
            int suggestionsOffered,
            int suggestionsAccepted,
            int suggestionsRejected,
            int suggestionsUndone) {
    }
}
