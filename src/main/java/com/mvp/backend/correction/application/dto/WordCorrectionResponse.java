package com.mvp.backend.correction.application.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mvp.backend.correction.domain.model.WordCorrection;

public record WordCorrectionResponse(
        UUID id,
        @JsonProperty("palabra_original") String originalWord,
        @JsonProperty("palabra_corregida") String correctedWord,
        @JsonProperty("posicion_inicio") int startPosition,
        @JsonProperty("posicion_fin") int endPosition) {

    public static WordCorrectionResponse from(WordCorrection correction) {
        return new WordCorrectionResponse(
                correction.getId(),
                correction.getOriginalWord(),
                correction.getCorrectedWord(),
                correction.getStartPosition(),
                correction.getEndPosition());
    }
}
