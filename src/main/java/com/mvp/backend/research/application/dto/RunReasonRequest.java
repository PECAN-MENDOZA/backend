package com.mvp.backend.research.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Motivo de una cancelacion o exclusion decidida por el investigador (queda registrado, nunca se borra). */
public record RunReasonRequest(@NotBlank @Size(min = 10, max = 500) String reason) {
}
