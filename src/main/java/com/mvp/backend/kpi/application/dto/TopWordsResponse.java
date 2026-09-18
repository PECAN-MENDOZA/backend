package com.mvp.backend.kpi.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TopWordsResponse(
        @JsonProperty("id_estudiante") UUID studentId,
        String from,
        String to,
        @JsonProperty("top_palabras") List<TopWordItem> topWords) {
}
