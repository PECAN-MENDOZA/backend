package com.mvp.backend.kpi.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorTypesResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        String from,
        String to,
        @JsonProperty("total_errores") long totalErrors,
        @JsonProperty("tipos_error") List<ErrorTypeItem> errorTypes) {
}
