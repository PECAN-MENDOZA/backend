package com.mvp.backend.sentencetest.application.dto;

/**
 * Metricas de una condicion (con o sin ayuda) sobre los intentos completados no excluidos.
 * participants: alumnos con al menos una oracion terminada y no omitida en la condicion.
 * acceptanceRate: solo con ayuda (null sin ayuda); ratePct y Wilson nulos si no se ofrecio nada.
 */
public record ConditionMetrics(
        int participants,
        MetricInterval errorsPer100Words,
        MetricInterval wordsPerMinute,
        AcceptanceRate acceptanceRate) {

    public record AcceptanceRate(long accepted, long offered, Double ratePct, Double wilsonLower, Double wilsonUpper) {
    }
}
