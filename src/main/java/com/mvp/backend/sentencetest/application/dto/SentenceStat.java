package com.mvp.backend.sentencetest.application.dto;

/**
 * Resumen por oracion sobre los intentos completados no excluidos. n: respuestas terminadas;
 * meanErrors: solo respuestas no omitidas con conteo conocido; meanDurationFirstKeyMs: solo con duracion.
 */
public record SentenceStat(
        int position,
        String kind,
        String assistance,
        int n,
        Double meanErrors,
        Double meanDurationFirstKeyMs,
        int skippedCount) {
}
