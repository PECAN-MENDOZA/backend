package com.mvp.backend.insights.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Actividad del salon en un periodo: cuantas correcciones pidio cada alumno y como respondio. */
public record ClassroomActivityResponse(
        UUID classroomId,
        String classroomName,
        String from,
        String to,
        List<StudentActivity> students) {

    public record StudentActivity(
            UUID studentId,
            String username,
            String realName,
            Instant lastActivityAt,
            long correctionsInPeriod,
            OutcomeCounts outcomes) {
    }

    public record OutcomeCounts(long edited, long accepted, long rejected, long undone, long unanswered) {
    }
}
