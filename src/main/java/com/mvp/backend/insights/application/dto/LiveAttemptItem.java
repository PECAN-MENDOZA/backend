package com.mvp.backend.insights.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Un intento en curso de un alumno vinculado al docente: en que oracion va. */
public record LiveAttemptItem(
        UUID attemptId,
        UUID studentId,
        String username,
        String realName,
        UUID classroomId,
        String classroomName,
        String testCode,
        String testTitle,
        int currentPosition,
        int sentenceCount,
        Instant startedAt) {
}
