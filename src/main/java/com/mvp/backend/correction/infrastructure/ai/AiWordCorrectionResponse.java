package com.mvp.backend.correction.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mvp.backend.correction.domain.model.ErrorType;

public record AiWordCorrectionResponse(
        @JsonProperty("palabra_original") String originalWord,
        @JsonProperty("palabra_corregida") String correctedWord,
        @JsonProperty("tipo_error") ErrorType errorType,
        double confidence,
        @JsonProperty("posicion_inicio") int startPosition,
        @JsonProperty("posicion_fin") int endPosition) {
}
