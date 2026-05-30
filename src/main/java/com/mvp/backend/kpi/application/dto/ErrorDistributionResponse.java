package com.mvp.backend.kpi.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorDistributionResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        String month,
        @JsonProperty("total_errores") long totalErrors,
        @JsonProperty("distribucion") List<ErrorDistributionItem> distribution) {
}
