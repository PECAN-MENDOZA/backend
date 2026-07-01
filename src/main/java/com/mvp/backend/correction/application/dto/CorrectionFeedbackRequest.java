package com.mvp.backend.correction.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CorrectionFeedbackRequest(
        @JsonProperty("sugerencia_elegida") @Size(max = 5000) String selectedSuggestion,
        @JsonProperty("acepto_correccion") @NotNull Boolean acceptedCorrection,
        @JsonProperty("texto_final") @Size(max = 5000) String finalText) {
}
