package com.mvp.backend.report.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportAvailabilityResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        @JsonProperty("alias_estudiante") String studentAlias,
        @JsonProperty("nombre_estudiante") String studentName,
        String month,
        boolean available,
        String source,
        @JsonProperty("generated_at") Instant generatedAt,
        String filename) {
}
