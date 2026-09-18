package com.mvp.backend.sentencetest.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Estado de un alumno asignado. attemptStatus: PENDING (sin intento), IN_PROGRESS, COMPLETED o CANCELLED
 * segun el intento mas reciente; currentPosition = oraciones terminadas + 1 solo mientras esta en curso.
 */
public record AssignmentStatusResponse(
        UUID studentId,
        String studentUsername,
        UUID classroomId,
        Instant assignedAt,
        UUID attemptId,
        String attemptStatus,
        Instant startedAt,
        Instant completedAt,
        Integer currentPosition,
        int sentenceCount,
        boolean excluded) {
}
