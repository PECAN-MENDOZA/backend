package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SplitMix64Tests {

    /** Valores dorados calculados con la implementacion de referencia en Python (Task 15 usa la misma). */
    @Test
    void seed42ProducesTheReferenceSequence() {
        SplitMix64 rng = new SplitMix64(42);
        assertThat(rng.nextLong()).isEqualTo(-4767286540954276203L);
        assertThat(rng.nextLong()).isEqualTo(2949826092126892291L);
        assertThat(rng.nextLong()).isEqualTo(5139283748462763858L);
    }

    @Test
    void nextIndexUsesUnsignedRemainderAndStaysInRange() {
        SplitMix64 rng = new SplitMix64(42);
        // Primer valor negativo como signed: el resto sin signo (2^64 - 4767286540954276203) mod 5.
        assertThat(rng.nextIndex(5)).isEqualTo((int) Long.remainderUnsigned(-4767286540954276203L, 5));
        for (int i = 0; i < 1000; i++) {
            assertThat(rng.nextIndex(7)).isBetween(0, 6);
        }
    }

    @Test
    void sameSeedGivesSameSequence() {
        SplitMix64 a = new SplitMix64(7);
        SplitMix64 b = new SplitMix64(7);
        for (int i = 0; i < 20; i++) {
            assertThat(a.nextLong()).isEqualTo(b.nextLong());
        }
    }
}
