package com.mvp.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ResearchPropertiesTests {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void absentThresholdsAreAllowed() {
        assertThatCode(() -> new ResearchProperties(TTL, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> new ResearchProperties(TTL, 2.5, 10.0)).doesNotThrowAnyException();
        assertThatCode(() -> new ResearchProperties(TTL, 0.001, 0.0)).doesNotThrowAnyException();
        assertThatCode(() -> new ResearchProperties(TTL, 100.0, 100.0)).doesNotThrowAnyException();
    }

    @Test
    void ppmMarginMustBeFiniteAndPositive() {
        for (double margin : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> new ResearchProperties(TTL, margin, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ppm-non-inferiority-margin");
        }
    }

    @Test
    void tasLimitMustBeAFinitePercentage() {
        for (double limit : new double[] {-0.1, 100.1, 150.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new ResearchProperties(TTL, null, limit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tas-limit");
        }
    }
}
