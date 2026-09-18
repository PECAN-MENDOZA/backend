package com.mvp.backend.sentencetest.application.service;

import java.util.Arrays;

import com.mvp.backend.sentencetest.application.dto.MetricInterval;

/**
 * IC 95 % bootstrap percentil de la media, determinista: 2000 remuestreos con SplitMix64 semilla 42.
 * Contrato replicado en Python (Task 15): por remuestreo se toman n indices en orden con nextIndex(n) y se
 * promedian; las 2000 medias se ordenan y se interpola R-7 en 0.025 y 0.975.
 */
final class Bootstrap {

    static final int RESAMPLES = 2000;
    static final long SEED = 42;

    private Bootstrap() {
    }

    static MetricInterval meanInterval(double[] values) {
        return meanInterval(values, RESAMPLES, SEED);
    }

    static MetricInterval meanInterval(double[] values, int resamples, long seed) {
        int n = values.length;
        if (n == 0) {
            return MetricInterval.EMPTY;
        }
        double mean = mean(values);
        if (n < 2) {
            return new MetricInterval(n, mean, null, null);
        }
        SplitMix64 rng = new SplitMix64(seed);
        double[] means = new double[resamples];
        for (int r = 0; r < resamples; r++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += values[rng.nextIndex(n)];
            }
            means[r] = sum / n;
        }
        Arrays.sort(means);
        return new MetricInterval(n, mean, quantile(means, 0.025), quantile(means, 0.975));
    }

    /** Cuantil R-7 (el de numpy por defecto) sobre una muestra ya ordenada. */
    static double quantile(double[] sorted, double p) {
        int k = sorted.length;
        double idx = p * (k - 1);
        int lo = (int) Math.floor(idx);
        double frac = idx - lo;
        int hi = Math.min(lo + 1, k - 1);
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }

    static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }
}
