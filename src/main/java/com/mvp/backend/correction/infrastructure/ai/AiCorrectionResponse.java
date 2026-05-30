package com.mvp.backend.correction.infrastructure.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiCorrectionResponse(
        @JsonProperty("texto_corregido") String correctedText,
        @JsonProperty("correcciones_realizadas") int correctionsCount,
        List<String> suggestions,
        double confidence,
        @JsonProperty("tiempo_procesamiento_ms") long processingTimeMs,
        @JsonProperty("palabras_corregidas") List<AiWordCorrectionResponse> correctedWords) {
}
