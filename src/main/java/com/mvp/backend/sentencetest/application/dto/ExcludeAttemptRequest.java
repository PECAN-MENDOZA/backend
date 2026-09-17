package com.mvp.backend.sentencetest.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Motivo de exclusion (10-500 caracteres; lo valida la entidad). */
public record ExcludeAttemptRequest(@NotBlank String reason) {
}
