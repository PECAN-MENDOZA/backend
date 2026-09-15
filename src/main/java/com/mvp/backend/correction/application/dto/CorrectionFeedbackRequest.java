package com.mvp.backend.correction.application.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Feedback del alumno sobre una sesion. {@code motivo} es opcional; el teclado envia
 * {@code "UNDO"} cuando el alumno deshace una sugerencia ya aplicada. El servicio trabaja siempre
 * con los valores efectivos ({@link #effectiveReason()}, {@link #effectiveAccepted()}), nunca con
 * los campos crudos: un UNDO es un rechazo envie lo que envie el flag.
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

    /** Motivo canonico (sin espacios, en mayusculas), o null si no vino: {@code "undo"} se guarda como {@code "UNDO"}. */
    public String effectiveReason() {
        return reason == null || reason.isBlank() ? null : reason.strip().toUpperCase(Locale.ROOT);
    }

    public boolean isUndo() {
        return REASON_UNDO.equals(effectiveReason());
    }

    /** Flag que realmente se aplica: deshacer una sugerencia equivale a rechazarla. */
    public boolean effectiveAccepted() {
        return !isUndo() && Boolean.TRUE.equals(acceptedCorrection);
    }
}
