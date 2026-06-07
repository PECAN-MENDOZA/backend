package com.mvp.backend.kpi.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TopWordItem(
        @JsonProperty("palabra_original") String originalWord,
        long frequency,
        @JsonProperty("veces_corregida_aceptada") long acceptedCorrectionCount) {
}
