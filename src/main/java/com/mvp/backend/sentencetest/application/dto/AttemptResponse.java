package com.mvp.backend.sentencetest.application.dto;

import java.util.List;
import java.util.UUID;

/** Intento del alumno. Las oraciones solo exponen posicion y asistencia: nunca el texto de referencia. */
public record AttemptResponse(
        UUID attemptId,
        UUID testId,
        String code,
        String title,
        Integer nextPosition,
        String status,
        List<SentenceSlot> sentences) {

    public record SentenceSlot(int position, String assistance) {
    }
}
