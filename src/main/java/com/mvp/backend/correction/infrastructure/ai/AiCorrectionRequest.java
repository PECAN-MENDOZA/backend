package com.mvp.backend.correction.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

record AiCorrectionRequest(
        @JsonProperty("texto_original") String originalText,
        @JsonProperty("contexto_adicional") String additionalContext) {
}
