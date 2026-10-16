package com.mvp.backend.report.infrastructure.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.mvp.backend.report.application.dto.ReportPdfDocument;

class SimplePdfGeneratorTests {

    private final SimplePdfGenerator generator = new SimplePdfGenerator();

    @Test
    void generatesPeriodReportWithAllTextSections() {
        ReportPdfDocument document = new ReportPdfDocument(
                "Reporte del periodo",
                "Nicolas Herrera",
                "student_015",
                "3.º B",
                false,
                "Sofia Garcia",
                "10/09/2026 – 17/09/2026",
                "2026-09-10",
                "2026-09-17",
                Instant.parse("2026-09-18T01:46:39Z"),
                new ReportPdfDocument.HelpSummary(16, 3, 8, 2, 0, 1, 2),
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
                "Reporte del periodo", "Nicolas Herrera", "student_015", "3.º B", false, "Sofia Garcia", "17/09/2026",
                "2026-09-17", "2026-09-17", Instant.parse("2026-09-18T01:46:39Z"),
                new ReportPdfDocument.HelpSummary(0, 0, 0, 0, 0, 0, 0), List.of(), List.of(), List.of(), null);

        byte[] pdf = generator.generate(document);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void headerShowsClassroomAndReadableDates() throws Exception {
        byte[] pdf = generator.generate(document(1, 1, "5.º A", true));

        String firstPage = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(firstPage).contains("Salón: 5.º A (archivado)");
        assertThat(firstPage).contains("Periodo: 10/09/2026");
        assertThat(firstPage).contains("17/09/2026");
        assertThat(firstPage).contains("Generado: 17/09/2026 20:46");
        assertThat(firstPage).doesNotContain("2026-09-10");
        assertThat(SimplePdfGenerator.classroomLine(document(0, 0, "5.º A", false))).isEqualTo("Salón: 5.º A");
    }

    /** Con distintos largos de contenido, ningun titulo queda solo al pie: su primera fila va en la misma pagina. */
    @Test
    void sectionHeadingsAreNeverLeftAloneAtTheBottomOfAPage() throws Exception {
        for (int words = 0; words <= 24; words++) {
            for (int writings = 0; writings <= 24; writings += 3) {
                byte[] pdf = generator.generate(document(words, writings, "3.º B", false));
                PdfReader reader = new PdfReader(pdf);
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    String text = extractor.getTextFromPage(page);
                    String where = "words=" + words + " writings=" + writings + " page=" + page;
                    if (text.contains("Últimas escrituras")) {
                        assertThat(text).as(where).containsAnyOf("Desenlace", "No hay escrituras");
                    }
                    if (text.contains("Notas del docente")) {
                        assertThat(text).as(where).contains("Seguimiento del periodo.");
                    }
                    if (text.contains("Palabras para practicar")) {
                        assertThat(text).as(where).containsAnyOf("Forma corregida", "Ninguna palabra");
                    }
                }
            }
        }
    }

    private static ReportPdfDocument document(int words, int writings, String classroom, boolean archived) {
        List<ReportPdfDocument.WordEntry> practice = new ArrayList<>();
        for (int i = 0; i < words; i++) {
            practice.add(new ReportPdfDocument.WordEntry("palabra" + i, "palabra " + i, 2));
        }
        List<ReportPdfDocument.WritingEntry> entries = new ArrayList<>();
        for (int i = 0; i < writings; i++) {
            entries.add(new ReportPdfDocument.WritingEntry(Instant.parse("2026-09-17T15:00:00Z"),
                    "el camion rojo " + i, "el camión rojo " + i, "Aceptó", false));
        }
        return new ReportPdfDocument(
                "Reporte del periodo", "Nicolas Herrera", "student_015", classroom, archived, "Sofia Garcia",
                "10/09/2026 – 17/09/2026", "2026-09-10", "2026-09-17", Instant.parse("2026-09-18T01:46:39Z"),
                new ReportPdfDocument.HelpSummary(16, 3, 8, 2, 0, 1, 2),
                List.of(new ReportPdfDocument.ErrorTypeSection("Tildes y acentuación", 6, List.of(
                        new ReportPdfDocument.WordEntry("camion", "camión", 3)))),
                practice, entries, "Seguimiento del periodo.");
    }
}
