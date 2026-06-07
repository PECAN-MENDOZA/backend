package com.mvp.backend.report.application.dto;

import java.time.Instant;
import java.util.List;

public record ReportPdfDocument(
        String title,
        String studentName,
        String studentAlias,
        String teacherName,
        String monthLabel,
        String monthCode,
        String sourceLabel,
        Instant generatedAt,
        double acceptanceRatePercentage,
        long totalSubmissions,
        long totalAccepted,
        long totalRejected,
        long unanswered,
        List<TopWordEntry> topWords,
        String teacherNotes) {

    public record TopWordEntry(
            String originalWord,
            long frequency,
            long acceptedCorrectionCount) {
    }
}
