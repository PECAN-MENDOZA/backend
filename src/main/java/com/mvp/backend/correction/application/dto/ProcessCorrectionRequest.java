package com.mvp.backend.correction.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProcessCorrectionRequest(
        @JsonProperty("texto_original") @NotBlank @Size(max = 5000) String originalText,
        @JsonProperty("contexto_adicional") @Size(max = 1000) String additionalContext) {
}
