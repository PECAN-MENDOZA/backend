package com.mvp.backend.correction.application.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Peticion de correccion. {@code id_ejecucion} es opcional: solo lo envia el teclado cuando el
 * alumno escribe dentro de una ejecucion experimental; en uso normal va ausente o nulo.
 */
public record ProcessCorrectionRequest(
        @JsonProperty("texto_original") @NotBlank @Size(max = 5000) String originalText,
        @JsonProperty("id_ejecucion") UUID experimentRunId) {

    public ProcessCorrectionRequest(String originalText) {
        this(originalText, null);
    }
}
