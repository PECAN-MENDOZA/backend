package com.mvp.backend.correction.infrastructure.ai;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Respuesta de la IA. {@code modelVersion} es opcional (la IA actual aun no lo envia): sigue el
 * contrato camelCase del servicio Flask y acepta {@code model_version} como alias por si la IA lo
 * expone en snake_case.
 */
public record AiCorrectionResponse(
        UUID studentId,
        String correctedText,
        long processingTimeMs,
        List<String> suggestions,
        @JsonAlias("model_version") String modelVersion) {
}
