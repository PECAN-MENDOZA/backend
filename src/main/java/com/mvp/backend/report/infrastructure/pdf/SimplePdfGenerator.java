package com.mvp.backend.report.infrastructure.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.mvp.backend.insights.domain.Period;
import com.mvp.backend.report.application.dto.ReportPdfDocument;
import com.mvp.backend.shared.exception.PdfGenerationException;

/** Compone el "Reporte del periodo": texto y tablas descriptivas; sin graficos, sin tendencias ni valoraciones. */
@Component
public class SimplePdfGenerator {

    private static final DateTimeFormatter WRITING_AT =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(Period.ZONE);
    private static final DateTimeFormatter GENERATED_AT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(Period.ZONE);

    // Alto aproximado de un titulo de seccion (interlineado + espacios) y del salto que lo precede.
    private static final float SECTION_HEIGHT = 40;
    private static final float SECTION_GAP = 18;

    private static final Color INK =new Color(37, 49, 63);
    private static final Color MUTED = new Color(105, 123, 140);
    private static final Color BORDER = new Color(219, 229, 239);
    private static final Color PANEL = new Color(247, 250, 255);

    private static final Font CARD_LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, MUTED);
    private static final Font CARD_VALUE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, INK);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, INK);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
    private static final Font BODY_BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK);
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
    private static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
    private static final Font FOOTER_BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);

    public byte[] generate(ReportPdfDocument document) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Document pdf = new Document(PageSize.LETTER, 42, 42, 68, 52);
            PdfWriter writer = PdfWriter.getInstance(pdf, output);
            writer.setPageEvent(new ReportPageEvent(document));
            pdf.open();

            pdf.add(firstPageSpacer());
            pdf.add(identityCard(document));
            addSection(pdf, writer, "Ayuda del teclado", helpCards(document.help()));
            addSection(pdf, writer, "Errores por tipo", errorTypesTable(document.errorTypes()));
            addSection(pdf, writer, "Palabras para practicar", practiceWordsTable(document.practiceWords()));
            addSection(pdf, writer, "Últimas escrituras", writingsTable(document.writings()));
            if (document.teacherNotes() != null && !document.teacherNotes().isBlank()) {
                addSection(pdf, writer, "Notas del docente", notesBlock(document.teacherNotes()));
            }
            pdf.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new PdfGenerationException("Failed to generate PDF report", exception);
        }
    }

    /**
     * Titulo de seccion y su contenido. Si al pie de la pagina no caben el titulo y la primera fila del
     * contenido (encabezado de la tabla + primera fila), se salta de pagina para que el titulo no quede huerfano.
     */
    private void addSection(Document pdf, PdfWriter writer, String title, PdfPTable content) {
        float needed = SECTION_HEIGHT + firstRowsHeight(pdf, content);
        if (writer.getVerticalPosition(true) - pdf.bottom() < SECTION_GAP + needed) {
            pdf.newPage();
        } else {
            pdf.add(Chunk.NEWLINE);
        }
        pdf.add(sectionTitle(title));
        pdf.add(content);
    }

    /** Alto del encabezado de la tabla y su primera fila; acotado para bloques mas altos que media pagina. */
    private static float firstRowsHeight(Document pdf, PdfPTable table) {
        table.setTotalWidth(pdf.right() - pdf.left());
        int rows = Math.min(table.getHeaderRows() + 1, table.size());
        float height = 0;
        for (int i = 0; i < rows; i++) {
            height += table.getRowHeight(i);
        }
        return Math.min(height, (pdf.top() - pdf.bottom()) / 2);
    }

    private Paragraph firstPageSpacer() {
        Paragraph spacer = new Paragraph(" ", BODY_FONT);
        spacer.setSpacingAfter(70);
        return spacer;
    }

    private PdfPTable identityCard(ReportPdfDocument document) {
        PdfPTable table = new PdfPTable(new float[] {1, 1});
        table.setWidthPercentage(100);
        table.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        PdfPCell left = panelCell(PANEL);
        left.addElement(labelParagraph("Estudiante"));
        left.addElement(valueParagraph(document.studentName(), 15));
        left.addElement(detailParagraph("Alias: " + document.studentAlias()));
        left.addElement(detailParagraph(classroomLine(document)));
        left.setPadding(18);

        PdfPCell right = panelCell(PANEL);
        right.addElement(labelParagraph("Docente"));
        right.addElement(valueParagraph(document.teacherName(), 13));
        right.addElement(detailParagraph("Periodo: " + document.periodLabel()));
        right.addElement(detailParagraph("Generado: " + GENERATED_AT.format(document.generatedAt())));
        right.setPadding(18);

        table.addCell(left);
        table.addCell(right);
        return table;
    }

    static String classroomLine(ReportPdfDocument document) {
        return "Salón: " + document.classroomName() + (document.classroomArchived() ? " (archivado)" : "");
    }

    /** Conteo por desenlace de las correcciones pedidas en el periodo. */
    private PdfPTable helpCards(ReportPdfDocument.HelpSummary help) {
        PdfPTable table = new PdfPTable(new float[] {1, 1, 1});
        table.setWidthPercentage(100);
        table.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        table.addCell(metricCell("Correcciones pedidas", help.total()));
        table.addCell(metricCell("Aceptó y editó", help.edited()));
        table.addCell(metricCell("Aceptó", help.accepted()));
        table.addCell(metricCell("Rechazó", help.rejected()));
        table.addCell(metricCell("Deshizo", help.undone()));
        table.addCell(metricCell("Sin respuesta", help.unanswered()));
        table.addCell(metricCell("Ya estaba bien", help.noChanges()));
        // Siete tarjetas en tres columnas: completa la ultima fila (una fila incompleta no se dibuja).
        table.completeRow();
        return table;
    }

    private PdfPTable errorTypesTable(List<ReportPdfDocument.ErrorTypeSection> types) {
        if (types.isEmpty()) {
            return emptyState("No hay palabras corregidas en este periodo.");
        }
        PdfPTable table = new PdfPTable(new float[] {2f, 0.8f, 3.2f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.addCell(headerCell("Tipo"));
        table.addCell(headerCell("Veces"));
        table.addCell(headerCell("Ejemplos"));
        for (ReportPdfDocument.ErrorTypeSection type : types) {
            table.addCell(bodyCell(type.label(), false));
            table.addCell(bodyCell(String.valueOf(type.count()), true));
            table.addCell(bodyCell(type.examples().stream()
                    .map(word -> word.original() + " -> " + word.corrected() + " (" + word.count() + ")")
                    .collect(Collectors.joining("; ")), false));
        }
        return table;
    }

    private PdfPTable practiceWordsTable(List<ReportPdfDocument.WordEntry> words) {
        if (words.isEmpty()) {
            return emptyState("Ninguna palabra se repitió en este periodo.");
        }
        PdfPTable table = new PdfPTable(new float[] {2.4f, 2.4f, 1.2f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.addCell(headerCell("Escribió"));
        table.addCell(headerCell("Forma corregida"));
        table.addCell(headerCell("Veces"));
        for (ReportPdfDocument.WordEntry word : words) {
            table.addCell(bodyCell(word.original(), false));
            table.addCell(bodyCell(word.corrected(), false));
            table.addCell(bodyCell(String.valueOf(word.count()), true));
        }
        return table;
    }

    /** Cada escritura: original -> final y el desenlace; "(prueba)" si se escribio durante una prueba. */
    private PdfPTable writingsTable(List<ReportPdfDocument.WritingEntry> writings) {
        if (writings.isEmpty()) {
            return emptyState("No hay escrituras en este periodo.");
        }
        PdfPTable table = new PdfPTable(new float[] {0.9f, 2.4f, 2.4f, 1.3f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.addCell(headerCell("Fecha"));
        table.addCell(headerCell("Escribió"));
        table.addCell(headerCell("Quedó"));
        table.addCell(headerCell("Desenlace"));
        for (ReportPdfDocument.WritingEntry writing : writings) {
            table.addCell(bodyCell(WRITING_AT.format(writing.createdAt()), false));
            table.addCell(bodyCell(writing.originalText(), false));
            table.addCell(bodyCell(writing.finalText(), false));
            table.addCell(bodyCell(writing.inTest() ? writing.outcomeLabel() + " (prueba)" : writing.outcomeLabel(), false));
        }
        return table;
    }

    private Paragraph sectionTitle(String title) {
        Paragraph paragraph = new Paragraph(title, SECTION_FONT);
        paragraph.setSpacingBefore(6);
        paragraph.setSpacingAfter(10);
        return paragraph;
    }

    private PdfPTable notesBlock(String notes) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        PdfPCell cell = panelCell(new Color(252, 252, 255));
        cell.setPadding(16);
        Paragraph body = new Paragraph(notes, BODY_FONT);
        body.setLeading(15);
        cell.addElement(body);
        table.addCell(cell);
        return table;
    }

    private PdfPTable emptyState(String message) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        PdfPCell cell = panelCell(new Color(249, 251, 255));
        cell.setPadding(14);
        cell.addElement(new Paragraph(message, FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, MUTED)));
        table.addCell(cell);
        return table;
    }

    private PdfPCell metricCell(String label, long value) {
        PdfPCell cell = panelCell(PANEL);
        cell.setPadding(14);
        Paragraph labelParagraph = new Paragraph(label, CARD_LABEL_FONT);
        labelParagraph.setSpacingAfter(8);
        cell.addElement(labelParagraph);
        cell.addElement(new Paragraph(String.valueOf(value), CARD_VALUE_FONT));
        return cell;
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(82, 99, 120))));
        cell.setBackgroundColor(new Color(240, 245, 252));
        cell.setBorderColor(BORDER);
        cell.setPadding(9);
        return cell;
    }

    private PdfPCell bodyCell(String text, boolean centered) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, centered ? BODY_BOLD_FONT : BODY_FONT));
        cell.setBorderColor(BORDER);
        cell.setPadding(8);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (centered) {
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        }
        return cell;
    }

    private PdfPCell panelCell(Color background) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER);
        cell.setBackgroundColor(background);
        return cell;
    }

    private Paragraph labelParagraph(String text) {
        Paragraph paragraph = new Paragraph(text, CARD_LABEL_FONT);
        paragraph.setSpacingAfter(6);
        return paragraph;
    }

    private Paragraph valueParagraph(String text, float size) {
        Paragraph paragraph = new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, INK));
        paragraph.setSpacingAfter(4);
        return paragraph;
    }

    private Paragraph detailParagraph(String text) {
        Paragraph paragraph = new Paragraph(text, BODY_FONT);
        paragraph.setLeading(14);
        return paragraph;
    }

    private static final class ReportPageEvent extends PdfPageEventHelper {

        private final ReportPdfDocument document;

        private ReportPageEvent(ReportPdfDocument document) {
            this.document = document;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document documentInstance) {
            PdfContentByte canvas = writer.getDirectContentUnder();
            float pageWidth = documentInstance.getPageSize().getWidth();
            float pageHeight = documentInstance.getPageSize().getHeight();

            canvas.saveState();
            canvas.setColorStroke(BORDER);
            canvas.setLineWidth(0.8f);
            canvas.rectangle(20, 20, pageWidth - 40, pageHeight - 40);
            canvas.stroke();
            canvas.restoreState();

            if (writer.getPageNumber() == 1) {
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase(document.title(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, INK)),
                        42, pageHeight - 72, 0);
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase("Qué escribió el estudiante, en qué se equivocó y qué hizo con la ayuda",
                                FontFactory.getFont(FontFactory.HELVETICA, 11, MUTED)),
                        42, pageHeight - 96, 0);
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase("Periodo: " + document.periodLabel(),
                                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK)),
                        42, pageHeight - 118, 0);
            }

            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(document.title(), FOOTER_BOLD_FONT), 42, 18, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Página " + writer.getPageNumber(), FOOTER_FONT), pageWidth - 42, 18, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(document.periodLabel() + " - " + document.studentAlias(), SMALL_FONT),
                    42, 6, 0);
        }
    }
}
