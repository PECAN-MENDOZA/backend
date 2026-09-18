package com.mvp.backend.sentencetest.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Conteo humano de errores de una oracion libre. */
public record AnnotateResponseRequest(@NotNull @Min(0) Integer errorCount) {
}
