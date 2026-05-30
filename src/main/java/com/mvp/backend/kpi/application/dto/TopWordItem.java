package com.mvp.backend.kpi.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mvp.backend.correction.domain.model.ErrorType;

public record TopWordItem(
        @JsonProperty("palabra_original") String originalWord,
        @JsonProperty("tipo_mas_comun") ErrorType mostCommonType,
        long frequency,
        @JsonProperty("confianza_promedio") double averageConfidence,
        @JsonProperty("veces_corregida_aceptada") long acceptedCorrectionCount) {
}
