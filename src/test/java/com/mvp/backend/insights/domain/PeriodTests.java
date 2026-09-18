package com.mvp.backend.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.mvp.backend.shared.exception.BusinessException;

class PeriodTests {

    // 2026-09-17 23:30 en Lima (UTC-5) = 2026-09-18 04:30Z: "hoy" debe ser el 17.
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToTodayInLima() {
        Period p = Period.parse(null, null, clock);
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(p.start()).isEqualTo(Instant.parse("2026-09-17T05:00:00Z"));
        assertThat(p.end()).isEqualTo(Instant.parse("2026-09-18T05:00:00Z"));
        assertThat(p.label()).isEqualTo("2026-09-17");
    }

    @Test
    void rangeIsInclusiveAndLabelled() {
        Period p = Period.parse("2026-09-10", "2026-09-17", clock);
        assertThat(p.end()).isEqualTo(Instant.parse("2026-09-18T05:00:00Z"));
        assertThat(p.label()).isEqualTo("2026-09-10 – 2026-09-17");
        assertThat(Period.parse("2026-09-10", null, clock).to()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void rejectsInvertedTooLongOrMalformed() {
        assertThatThrownBy(() -> Period.parse("2026-09-17", "2026-09-10", clock)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Period.parse("2026-01-01", "2026-06-30", clock)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Period.parse("17/09/2026", null, clock)).isInstanceOf(BusinessException.class);
    }
}
