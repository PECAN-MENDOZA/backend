package com.mvp.backend.sentencetest.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Oracion del editor: kind DICTATED|FREE, assistance ASSISTED|UNASSISTED (se validan en el servicio con mensaje claro). */
public record SentenceInput(
        @NotBlank String kind,
        @NotBlank @Size(max = 500) String referenceText,
        @NotBlank String assistance) {
}
