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
        List<ErrorEntry> errorDistribution,
        List<TopWordEntry> topWords,
        String teacherNotes) {

    public record ErrorEntry(String label, long count, double percentage) {
    }

    public record TopWordEntry(
            String originalWord,
            String errorType,
            long frequency,
            double averageConfidence,
            long acceptedCorrectionCount) {
    }
}
