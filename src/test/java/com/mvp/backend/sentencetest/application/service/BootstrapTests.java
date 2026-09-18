package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.mvp.backend.sentencetest.application.dto.MetricInterval;

class BootstrapTests {

    private static final double[] ONE_TO_FIVE = {1, 2, 3, 4, 5};

    @Test
    void meanIntervalBracketsTheMeanWithinTheSampleRange() {
        MetricInterval interval = Bootstrap.meanInterval(ONE_TO_FIVE);
        assertThat(interval.n()).isEqualTo(5);
        assertThat(interval.mean()).isEqualTo(3.0);
        assertThat(interval.lower()).isLessThan(3.0).isGreaterThanOrEqualTo(1.0);
        assertThat(interval.upper()).isGreaterThan(3.0).isLessThanOrEqualTo(5.0);
    }

    /** Valores dorados fijados tras la primera ejecucion; el script Python de la Task 15 afirma los mismos. */
    @Test
    void goldenIntervalForOneToFive() {
        MetricInterval interval = Bootstrap.meanInterval(ONE_TO_FIVE);
        assertThat(interval.lower()).isCloseTo(1.800000000000, within(1e-9));
        assertThat(interval.upper()).isCloseTo(4.200000000000, within(1e-9));
    }

    @Test
    void isDeterministic() {
        double[] values = {12.5, 3.25, 40, 7.75, 0, 18};
        MetricInterval first = Bootstrap.meanInterval(values);
        MetricInterval second = Bootstrap.meanInterval(values);
        assertThat(first).isEqualTo(second);
        assertThat(Bootstrap.meanInterval(values, 2000, 42)).isEqualTo(first);
        assertThat(Bootstrap.meanInterval(values, 2000, 43)).isNotEqualTo(first);
    }

    @Test
    void singleValueHasNoBounds() {
        MetricInterval interval = Bootstrap.meanInterval(new double[] {4.5});
        assertThat(interval.n()).isEqualTo(1);
        assertThat(interval.mean()).isEqualTo(4.5);
        assertThat(interval.lower()).isNull();
        assertThat(interval.upper()).isNull();
    }

    @Test
    void emptyInputHasNoMeanNorBounds() {
        MetricInterval interval = Bootstrap.meanInterval(new double[0]);
        assertThat(interval.n()).isZero();
        assertThat(interval.mean()).isNull();
        assertThat(interval.lower()).isNull();
        assertThat(interval.upper()).isNull();
    }

    @Test
    void constantSampleCollapsesToThePoint() {
        MetricInterval interval = Bootstrap.meanInterval(new double[] {2, 2, 2, 2});
        assertThat(interval.lower()).isEqualTo(2.0);
        assertThat(interval.upper()).isEqualTo(2.0);
    }
}
