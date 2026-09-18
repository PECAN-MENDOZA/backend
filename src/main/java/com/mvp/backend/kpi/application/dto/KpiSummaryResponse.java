package com.mvp.backend.kpi.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KpiSummaryResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        String name,
        String from,
        String to,
        @JsonProperty("tasa_aceptacion") AcceptanceRateResponse acceptanceRate,
        @JsonProperty("top_palabras") List<TopWordItem> topWords) {
}
