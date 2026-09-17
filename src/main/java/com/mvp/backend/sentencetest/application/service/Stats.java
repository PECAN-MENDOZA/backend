package com.mvp.backend.sentencetest.application.service;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.inference.TTest;

/** Intervalo de Wilson, IC t de una media y resumen t pareado (antes en StudyMetricsService). */
final class Stats {

    /** Cuantil 0.975 de la normal estandar usado en el intervalo de Wilson. */
    static final double Z_95 = 1.959964;
    private static final double CONFIDENCE = 0.95;

    private Stats() {
    }

    /** Media, desviacion muestral e IC 95 % (t) de un valor por alumno; sin IC con n < 2 o varianza nula. */
    record Interval(int n, Double mean, Double sd, Double lower, Double upper) {
        boolean inferential() {
            return lower != null;
        }
    }

    /** Intervalo de Wilson (95 %) de una proporcion agregada, en porcentaje y acotado a [0, 100]. */
    record WilsonInterval(Double lower, Double upper) {
    }

    /** Comparacion emparejada assisted - unassisted; t, p y dz solo con n >= 2 y varianza no nula. */
    record PairedSummary(int n, Double meanAssisted, Double meanUnassisted, Double meanDelta, Double sdDelta,
            Double lower, Double upper, Double t, Double p, Double dz) {
    }

    static Interval interval(double[] data) {
        int n = data.length;
        if (n == 0) {
            return new Interval(0, null, null, null, null);
        }
        double mean = Bootstrap.mean(data);
        if (n < 2) {
            return new Interval(n, mean, null, null, null);
        }
        double sd = sampleSd(data);
        if (sd == 0.0) {
            return new Interval(n, mean, sd, null, null);
        }
        double halfWidth = new TDistribution(n - 1).inverseCumulativeProbability(1 - (1 - CONFIDENCE) / 2)
                * sd / Math.sqrt(n);
        return new Interval(n, mean, sd, mean - halfWidth, mean + halfWidth);
    }

    /**
     * Wilson score interval: centro = (p + z^2/2n) / (1 + z^2/n),
     * semiancho = z / (1 + z^2/n) * sqrt(p(1 - p)/n + z^2/4n^2), con p = successes / n y z = Z_95.
     * Sin denominador (n == 0) no hay intervalo.
     */
    static WilsonInterval wilson(long successes, long n) {
        if (n <= 0) {
            return new WilsonInterval(null, null);
        }
        double p = (double) successes / n;
        double z2n = Z_95 * Z_95 / n;
        double center = (p + z2n / 2.0) / (1.0 + z2n);
        double half = Z_95 / (1.0 + z2n) * Math.sqrt(p * (1.0 - p) / n + Z_95 * Z_95 / (4.0 * n * n));
        return new WilsonInterval(clampPercent(100.0 * (center - half)), clampPercent(100.0 * (center + half)));
    }

    static PairedSummary pairedSummary(double[] assisted, double[] unassisted) {
        int n = assisted.length;
        if (n == 0) {
            return new PairedSummary(0, null, null, null, null, null, null, null, null, null);
        }
        double[] deltas = new double[n];
        for (int i = 0; i < n; i++) {
            deltas[i] = assisted[i] - unassisted[i];
        }
        Interval delta = interval(deltas);
        Double t = null;
        Double p = null;
        Double dz = null;
        if (delta.inferential()) {
            TTest test = new TTest();
            t = test.pairedT(assisted, unassisted);
            p = test.pairedTTest(assisted, unassisted);
            dz = delta.mean() / delta.sd();
        }
        return new PairedSummary(n, Bootstrap.mean(assisted), Bootstrap.mean(unassisted), delta.mean(), delta.sd(),
                delta.lower(), delta.upper(), t, p, dz);
    }

    private static double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static double sampleSd(double[] data) {
        double mean = Bootstrap.mean(data);
        double squares = 0;
        for (double value : data) {
            squares += (value - mean) * (value - mean);
        }
        return Math.sqrt(squares / (data.length - 1));
    }
}
