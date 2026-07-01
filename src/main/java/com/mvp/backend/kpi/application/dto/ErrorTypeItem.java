package com.mvp.backend.kpi.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorTypeItem(
        @JsonProperty("tipo") String errorType,
        @JsonProperty("nombre") String label,
        @JsonProperty("cantidad") long count,
        @JsonProperty("porcentaje") double percentage) {
}
