package com.mvp.backend.kpi.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AcceptanceTrendResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        @JsonProperty("desde") String from,
        @JsonProperty("hasta") String to,
        @JsonProperty("serie") List<MonthlyAcceptancePoint> series) {
}
