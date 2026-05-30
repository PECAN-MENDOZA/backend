package com.mvp.backend.correction.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mvp.backend.correction.domain.model.CorrectionSession;

public record CorrectionSessionResponse(
        @JsonProperty("id_sesion") UUID sessionId,
        @JsonProperty("texto_original") String originalText,
        @JsonProperty("texto_corregido") String correctedText,
        @JsonProperty("correcciones_realizadas") int correctionsCount,
        List<String> suggestions,
        Double confidence,
        @JsonProperty("sugerencia_elegida") String selectedSuggestion,
        @JsonProperty("acepto_correccion") Boolean acceptedCorrection,
        @JsonProperty("tiempo_respuesta_ms") Long responseTimeMs,
        @JsonProperty("palabras_corregidas") List<WordCorrectionResponse> correctedWords,
        Instant createdAt) {

    public static CorrectionSessionResponse from(
            CorrectionSession session,
            List<String> suggestions,
            List<WordCorrectionResponse> correctedWords) {
        return new CorrectionSessionResponse(
                session.getId(),
                session.getOriginalText(),
                session.getCorrectedText(),
                session.getCorrectionsCount(),
                suggestions,
                session.getConfidence(),
                session.getSelectedSuggestion(),
                session.getAcceptedCorrection(),
                session.getResponseTimeMs(),
                correctedWords,
                session.getCreatedAt());
    }
}
