package com.mvp.backend.sentencetest.application.dto;

/** Media de n valores por alumno con IC 95 % bootstrap percentil; sin limites cuando n < 2. */
public record MetricInterval(int n, Double mean, Double lower, Double upper) {

    public static final MetricInterval EMPTY = new MetricInterval(0, null, null, null);
}
