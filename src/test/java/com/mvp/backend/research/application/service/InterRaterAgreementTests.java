package com.mvp.backend.research.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mvp.backend.research.application.dto.AgreementSummary;

class InterRaterAgreementTests {

    @Test
    void linearlyWeightedKappaOverObservedValues() {
        // k-1 = 2. Do = (0+1+0+1)/(4*2) = 0.25. p1 = p2 = (.25, .25, .5):
        // De = [2*(.25*.25*1) + 2*(.25*.5*2) + 2*(.25*.5*1)] / 2 = 0.875/2 = 0.4375 ; kappa = 1 - .25/.4375
        AgreementSummary summary = InterRaterAgreement.of(List.of(0, 1, 2, 2), List.of(0, 2, 2, 1));

        assertThat(summary.complete()).isTrue();
        assertThat(summary.weightedKappa()).isCloseTo(0.4286, within(1e-3));
        assertThat(summary.exactAgreement()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void sparseHugeCountsFinishInstantly() {
        AgreementSummary summary = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> InterRaterAgreement.of(List.of(0, 1_000_000), List.of(1_000_000, 0)));

        assertThat(summary.complete()).isTrue();
        assertThat(summary.exactAgreement()).isZero();
        // Do = 1, De = 2 * (.5 * .5 * 1) = 0.5 -> kappa = -1 (total disagreement).
        assertThat(summary.weightedKappa()).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void kappaIsUndefinedWithoutObservedRange() {
        assertThat(InterRaterAgreement.of(List.of(4, 4, 4), List.of(4, 4, 4)))
                .isEqualTo(new AgreementSummary(true, null, 1.0));
        assertThat(InterRaterAgreement.of(List.of(1, 1), List.of(2, 2)).weightedKappa()).isCloseTo(0.0, within(1e-9));
    }
}
