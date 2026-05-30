package com.mvp.backend.correction.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Size;

public record CorrectionFeedbackRequest(
        @JsonProperty("sugerencia_elegida") @Size(max = 5000) String selectedSuggestion,
        @JsonProperty("acepto_correccion") boolean acceptedCorrection) {
}
