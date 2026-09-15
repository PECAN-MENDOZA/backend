package com.mvp.backend.correction.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Feedback del alumno sobre una sesion. {@code motivo} es opcional; el teclado envia
 * {@code "UNDO"} cuando el alumno deshace una sugerencia ya aplicada.
 */
public record CorrectionFeedbackRequest(
        @JsonProperty("sugerencia_elegida") @Size(max = 5000) String selectedSuggestion,
        @JsonProperty("acepto_correccion") @NotNull Boolean acceptedCorrection,
        @JsonProperty("texto_final") @Size(max = 5000) String finalText,
        @JsonProperty("motivo") @Size(max = 40) String reason) {

    public static final String REASON_UNDO = "UNDO";

    public CorrectionFeedbackRequest(String selectedSuggestion, Boolean acceptedCorrection, String finalText) {
        this(selectedSuggestion, acceptedCorrection, finalText, null);
    }

    /** Motivo sin espacios, o null si no vino. */
    public String normalizedReason() {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }

    public boolean isUndo() {
        return REASON_UNDO.equalsIgnoreCase(normalizedReason());
    }
}
