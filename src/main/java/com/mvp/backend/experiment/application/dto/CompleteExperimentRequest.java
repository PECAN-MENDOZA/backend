package com.mvp.backend.experiment.application.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CompleteExperimentRequest(
        @JsonProperty("texto_final") @NotBlank @Size(max = 10000) String finalText,
        @JsonProperty("duracion_ms") @NotNull @Positive Long durationMs,
        @JsonProperty("completion_key") @NotNull UUID completionKey,
        @JsonProperty("app_version") @NotBlank @Size(max = 80) String appVersion) {
}
