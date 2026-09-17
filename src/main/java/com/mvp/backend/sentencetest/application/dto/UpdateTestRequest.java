package com.mvp.backend.sentencetest.application.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Edicion de un borrador: reemplaza titulo, notas y todas las oraciones (position = indice + 1). */
public record UpdateTestRequest(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 2000) String notes,
        @NotNull @Size(min = 1, max = 60) List<@Valid SentenceInput> sentences) {
}
