package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.research.domain.model.TechnicalEvaluation;

/** Evaluacion tecnica tal como se registro; {@code fOne} es descriptivo y se deriva de P y R. */
public record TechnicalEvaluationResponse(
        UUID id,
        String modelVersion,
        String datasetSha256,
        String scorerVersion,
        double precision,
        double recall,
        double fZeroFive,
        double fOne,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        Instant createdAt) {

    public static TechnicalEvaluationResponse from(TechnicalEvaluation evaluation) {
        return new TechnicalEvaluationResponse(
                evaluation.getId(),
                evaluation.getModelVersion(),
                evaluation.getDatasetSha256(),
                evaluation.getScorerVersion(),
                evaluation.getPrecisionValue(),
                evaluation.getRecallValue(),
                evaluation.getFZeroFive(),
                TechnicalEvaluation.fOne(evaluation.getPrecisionValue(), evaluation.getRecallValue()),
                evaluation.getTruePositives(),
                evaluation.getFalsePositives(),
                evaluation.getFalseNegatives(),
                evaluation.getCreatedAt());
    }
}
