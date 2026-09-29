package com.mvp.backend.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutcomeTests {

    @Test
    void derivesFromExistingColumns() {
        assertThat(Outcome.of(true, true, null)).isEqualTo(Outcome.EDITED);
        assertThat(Outcome.of(true, false, null)).isEqualTo(Outcome.ACCEPTED);
        assertThat(Outcome.of(false, false, "UNDO")).isEqualTo(Outcome.UNDONE);
        assertThat(Outcome.of(false, false, null)).isEqualTo(Outcome.REJECTED);
        assertThat(Outcome.of(null, false, null)).isEqualTo(Outcome.UNANSWERED);
        assertThat(Outcome.EDITED.label()).isEqualTo("Aceptó y editó");
    }
}
