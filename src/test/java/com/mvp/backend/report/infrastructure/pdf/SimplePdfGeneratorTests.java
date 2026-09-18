package com.mvp.backend.report.infrastructure.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mvp.backend.report.application.dto.ReportPdfDocument;

class SimplePdfGeneratorTests {

    private final SimplePdfGenerator generator = new SimplePdfGenerator();

    @Test
    void generatesPeriodReportWithAllTextSections() {
        ReportPdfDocument document = new ReportPdfDocument(
                "Reporte del periodo",
                "Nicolas Herrera",
                "student_015",
                "Sofia Garcia",
                "2026-09-10 – 2026-09-17",
                "2026-09-10",
                "2026-09-17",
                Instant.parse("2026-09-18T01:46:39Z"),
                new ReportPdfDocument.HelpSummary(14, 3, 8, 2, 0, 1),
                List.of(new ReportPdfDocument.ErrorTypeSection("Tildes y acentuación", 6, List.of(
                        new ReportPdfDocument.WordEntry("camion", "camión", 3),
                        new ReportPdfDocument.WordEntry("papa", "papá", 2)))),
                List.of(new ReportPdfDocument.WordEntry("camion", "camión", 3)),
                List.of(new ReportPdfDocument.WritingEntry(
                        Instant.parse("2026-09-17T15:00:00Z"), "el camion rojo", "el camión rojo", "Aceptó", false)),
                "Seguimiento del periodo.");

        byte[] pdf = generator.generate(document);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.");
        assertThat(pdf.length).isGreaterThan(1500);
    }

    @Test
    void generatesPdfWhenThePeriodHasNoData() {
        ReportPdfDocument document = new ReportPdfDocument(
                "Reporte del periodo", "Nicolas Herrera", "student_015", "Sofia Garcia", "2026-09-17",
                "2026-09-17", "2026-09-17", Instant.parse("2026-09-18T01:46:39Z"),
                new ReportPdfDocument.HelpSummary(0, 0, 0, 0, 0, 0), List.of(), List.of(), List.of(), null);

        byte[] pdf = generator.generate(document);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }
}
