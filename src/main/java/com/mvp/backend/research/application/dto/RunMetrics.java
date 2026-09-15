package com.mvp.backend.research.application.dto;

/**
 * Metricas de una ejecucion: palabras del texto final (tokenizacion Unicode documentada en
 * {@code StudyMetricsService.WORD}), PEO ({@code null} si no hay palabras contables) y PPM.
 */
public record RunMetrics(int wordCount, Double peo, double ppm) {
}
