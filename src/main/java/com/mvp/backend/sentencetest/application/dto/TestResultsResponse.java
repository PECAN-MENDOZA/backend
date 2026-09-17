package com.mvp.backend.sentencetest.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mvp.backend.sentencetest.domain.model.Assistance;

/**
 * Resultados de una prueba. sample.completed incluye los excluidos (sample.excluded los cuenta aparte);
 * las metricas usan solo completados no excluidos. incomplete: hay libres sin anotar.
 * sampleInsufficient: completados no excluidos < minSample. designManual siempre true (condicion fijada a mano).
 * datasetSha256: SHA-256 del CSV de exportacion de esta misma cohorte.
 */
public record TestResultsResponse(
        UUID testId,
        String code,
        String title,
        String status,
        Instant computedAt,
        Sample sample,
        boolean incomplete,
        boolean sampleInsufficient,
        int minSample,
        boolean designManual,
        Map<Assistance, ConditionMetrics> conditions,
        Paired paired,
        List<SentenceStat> sentences,
        Provenance provenance,
        String datasetSha256) {

    public record Sample(int assigned, int completed, int excluded, int cancelled, int inProgress, int unannotatedFree) {
    }

    public record Paired(PairedDelta errorsPer100Words, PairedDelta wordsPerMinute) {
    }

    public record Provenance(List<String> modelVersions, List<String> appVersions, List<String> backendVersions) {
    }
}
