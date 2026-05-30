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
    void generatesPdfWithProfessionalLayoutStructure() {
        ReportPdfDocument document = new ReportPdfDocument(
                "Reporte mensual consolidado",
                "Nicolas Herrera",
                "student_015",
                "Sofia Garcia",
                "mayo 2026",
                "2026-05",
                "Snapshot historico cerrado",
                Instant.parse("2026-05-30T01:46:39Z"),
                78.57,
                14,
                11,
                3,
                0,
                List.of(
                        new ReportPdfDocument.ErrorEntry("ortografico", 9, 56.25),
                        new ReportPdfDocument.ErrorEntry("fonologico", 5, 31.25),
                        new ReportPdfDocument.ErrorEntry("semantico", 2, 12.50)),
                List.of(
                        new ReportPdfDocument.TopWordEntry("ermano", "ortografico", 2, 0.95, 2),
                        new ReportPdfDocument.TopWordEntry("zoolojico", "ortografico", 2, 0.97, 2)),
                "Seguimiento positivo durante el mes con buena aceptacion de sugerencias.");

        byte[] pdf = generator.generate(document);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.");
        assertThat(pdf.length).isGreaterThan(2500);
    }
}
