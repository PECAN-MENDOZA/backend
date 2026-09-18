package com.mvp.backend.sentencetest.application.service;

/**
 * PRNG SplitMix64 (Steele, Lea y Flood 2014). Se replica en Python (scripts/analyze_sentence_tests.py)
 * para que ambos lados produzcan exactamente el mismo bootstrap con la misma semilla.
 */
final class SplitMix64 {

    private long state;

    SplitMix64(long seed) {
        this.state = seed;
    }

    long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Indice uniforme en [0, n) con resto sin signo (equivale a {@code value % n} sobre 64 bits sin signo en Python). */
    int nextIndex(int n) {
        return (int) Long.remainderUnsigned(nextLong(), n);
    }
}
