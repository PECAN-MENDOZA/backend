package com.mvp.backend.kpi.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MonthlyAcceptancePoint(
        String month,
        @JsonProperty("total_envios") long total,
        @JsonProperty("total_aceptadas") long accepted,
        @JsonProperty("tasa_aceptacion_pct") double percentage) {
}
