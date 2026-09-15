package com.mvp.backend.research.application.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Resultado de una categoria de error dentro de una evaluacion tecnica (spec §11.4). Mismas reglas que
 * el vector global: P y R deben reproducirse desde TP/FP/FN y F0.5 desde P y R (tolerancia 1e-6).
 */
public record CategoryResult(
        @NotBlank @Size(max = 80) String category,
        @NotNull @Min(0) Integer tp,
        @NotNull @Min(0) Integer fp,
        @NotNull @Min(0) Integer fn,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double precision,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double recall,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double f05) {
}
