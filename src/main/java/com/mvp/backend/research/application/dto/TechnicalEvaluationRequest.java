package com.mvp.backend.research.application.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registro de una evaluacion tecnica. El backend valida el hash del conjunto y el scorer, no nombres
 * de archivo locales que no puede observar: {@code data/eval_gold.csv} y {@code pruebas.txt} se
 * rechazan como conjunto final por documentacion y por la interfaz, no aqui.
 */
public record TechnicalEvaluationRequest(
        @NotBlank @Size(max = 160) String modelVersion,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "must be a lowercase hex SHA-256") String datasetSha256,
        @NotBlank @Pattern(regexp = "exact_token_edits_v1", message = "must be exact_token_edits_v1") String scorerVersion,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double precision,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double recall,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double fZeroFive,
        @NotNull @Min(0) Integer truePositives,
        @NotNull @Min(0) Integer falsePositives,
        @NotNull @Min(0) Integer falseNegatives) {
}
