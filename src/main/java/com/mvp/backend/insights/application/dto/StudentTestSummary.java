package com.mvp.backend.insights.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Un intento terminado de una prueba de oraciones, oracion por oracion, tal como lo ve el docente. */
public record StudentTestSummary(
        UUID attemptId,
        String testCode,
        String testTitle,
        Instant completedAt,
        boolean excluded,
        List<SentenceSummary> sentences) {

    /** errorCount es el efectivo (automatico en dictado, anotado en libre; null si falta anotar). */
    public record SentenceSummary(
            int position,
            String kind,
            String assistance,
            String referenceText,
            String finalText,
            boolean skipped,
            Integer errorCount,
            String errorSource,
            List<Edit> edits,
            Long durationFromFirstKeyMs) {
    }

    public record Edit(String type, String expected, String written) {
    }
}
