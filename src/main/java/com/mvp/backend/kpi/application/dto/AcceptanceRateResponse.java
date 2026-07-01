package com.mvp.backend.kpi.application.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AcceptanceRateResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        String month,
        @JsonProperty("total_envios") long totalSubmissions,
        @JsonProperty("total_aceptadas") long totalAccepted,
        @JsonProperty("total_rechazadas") long totalRejected,
        @JsonProperty("sin_respuesta") long unanswered,
        @JsonProperty("total_editadas") long totalEdited,
        @JsonProperty("tasa_aceptacion_pct") double acceptanceRatePercentage) {
}
