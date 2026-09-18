package com.mvp.backend.report.application.dto;

import java.time.Instant;
import java.util.List;

/** Contenido del "Reporte del periodo": solo texto descriptivo, sin graficos ni tendencias. */
public record ReportPdfDocument(
        String title,
        String studentName,
        String studentAlias,
        String teacherName,
        String periodLabel,
        String from,
        String to,
        Instant generatedAt,
        HelpSummary help,
        List<ErrorTypeSection> errorTypes,
        List<WordEntry> practiceWords,
        List<WritingEntry> writings,
        String teacherNotes) {

    /** Cuantas veces el alumno pidio ayuda en el periodo y que hizo con ella. */
    public record HelpSummary(long total, long edited, long accepted, long rejected, long undone, long unanswered) {
    }

    public record ErrorTypeSection(String label, long count, List<WordEntry> examples) {
    }

    public record WordEntry(String original, String corrected, long count) {
    }

    public record WritingEntry(Instant createdAt, String originalText, String finalText, String outcomeLabel,
            boolean inTest) {
    }
}
