package com.mvp.backend.correction.application.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Peticion de correccion. {@code id_respuesta} es opcional: solo lo envia el teclado cuando el
 * alumno escribe una oracion de una prueba (respuesta abierta); en uso normal va ausente o nulo.
 */
public record ProcessCorrectionRequest(
        @JsonProperty("texto_original") @NotBlank @Size(max = 5000) String originalText,
        @JsonProperty("id_respuesta") UUID testResponseId) {

    public ProcessCorrectionRequest(String originalText) {
        this(originalText, null);
    }
}
