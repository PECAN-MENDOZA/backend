package com.mvp.backend.insights.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import com.mvp.backend.shared.exception.BusinessException;

/** Periodo del panel docente: fechas inclusivas en Lima convertidas a [start, end) en UTC. */
public record Period(LocalDate from, LocalDate to, Instant start, Instant end) {

    public static final ZoneId ZONE = ZoneId.of("America/Lima");
    public static final int MAX_DAYS = 92;

    public static Period parse(String fromText, String toText, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate from = fromText == null || fromText.isBlank() ? today : parseDate(fromText);
        LocalDate to = toText == null || toText.isBlank() ? from : parseDate(toText);
        if (to.isBefore(from)) {
            throw new BusinessException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BusinessException("Period must not exceed " + MAX_DAYS + " days");
        }
        return new Period(from, to, from.atStartOfDay(ZONE).toInstant(), to.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new BusinessException("Dates must use the YYYY-MM-DD format");
        }
    }

    public String label() {
        return from.equals(to) ? from.toString() : from + " – " + to;
    }
}
