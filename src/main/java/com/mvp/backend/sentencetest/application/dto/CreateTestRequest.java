package com.mvp.backend.sentencetest.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de una prueba en borrador. El codigo se valida en el servicio ({@code ^[A-Z0-9-]{3,40}$}). */
public record CreateTestRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String title,
        @Size(max = 2000) String notes) {
}
