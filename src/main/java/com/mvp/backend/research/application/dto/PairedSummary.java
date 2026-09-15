package com.mvp.backend.research.application.dto;

/**
 * Comparacion emparejada ASSISTED - UNASSISTED con un valor por participante y condicion.
 * Con {@code n < 2}, o con diferencias de varianza nula, los campos inferenciales (IC 95 % con
 * distribucion t, estadistico t, valor p bilateral y d_z de Cohen) quedan en {@code null}.
 */
public record PairedSummary(
        int n,
        Double assistedMean,
        Double assistedSd,
        Double unassistedMean,
        Double unassistedSd,
        Double meanDelta,
        Double sdDelta,
        Double ci95Lower,
        Double ci95Upper,
        Double tStatistic,
        Double pValue,
        Double cohenDz) {

    public static PairedSummary empty() {
        return new PairedSummary(0, null, null, null, null, null, null, null, null, null, null, null);
    }
}
