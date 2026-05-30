package com.mvp.backend.report.infrastructure.pdf;

import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

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
import com.mvp.backend.report.application.dto.ReportPdfDocument;
import com.mvp.backend.shared.exception.PdfGenerationException;

@Component
public class SimplePdfGenerator {

    private static final DateTimeFormatter GENERATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'", new Locale("es", "PE"));

    private static final Color NAVY = new Color(41, 84, 140);
    private static final Color GOLD = new Color(232, 176, 64);
    private static final Color INK = new Color(37, 49, 63);
    private static final Color MUTED = new Color(105, 123, 140);
    private static final Color BORDER = new Color(219, 229, 239);
    private static final Color PANEL = new Color(247, 250, 255);
    private static final Color SUCCESS_PANEL = new Color(233, 248, 237);
    private static final Color WARNING_PANEL = new Color(255, 243, 235);
    private static final Color EMPTY_PANEL = new Color(248, 242, 255);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, Color.WHITE);
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 11, new Color(232, 240, 255));
    private static final Font HERO_META_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
    private static final Font HERO_SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(232, 240, 255));
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
            pdf.add(Chunk.NEWLINE);
            pdf.add(metricCards(document));
            pdf.add(Chunk.NEWLINE);
            pdf.add(sectionTitle("Distribucion de errores"));
            pdf.add(errorDistributionTable(document.errorDistribution()));
            pdf.add(Chunk.NEWLINE);
            pdf.add(sectionTitle("Palabras recurrentes"));
            pdf.add(topWordsTable(document.topWords()));
            pdf.add(Chunk.NEWLINE);
            pdf.add(sectionTitle("Notas docentes"));
            pdf.add(notesBlock(document.teacherNotes()));
            pdf.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new PdfGenerationException("Failed to generate PDF report", exception);
        }
    }

    private Paragraph firstPageSpacer() {
        Paragraph spacer = new Paragraph(" ", BODY_FONT);
        spacer.setSpacingAfter(90);
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
        left.setPadding(18);

        PdfPCell right = panelCell(PANEL);
        right.addElement(labelParagraph("Docente"));
        right.addElement(valueParagraph(document.teacherName(), 13));
        right.addElement(detailParagraph("Periodo: " + document.monthCode()));
        right.setPadding(18);

        table.addCell(left);
        table.addCell(right);
        return table;
    }

    private PdfPTable metricCards(ReportPdfDocument document) {
        PdfPTable table = new PdfPTable(new float[] {1, 1, 1, 1});
        table.setWidthPercentage(100);
        table.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        table.addCell(metricCell("Envios", String.valueOf(document.totalSubmissions()), PANEL));
        table.addCell(metricCell("Aceptadas", String.valueOf(document.totalAccepted()), SUCCESS_PANEL));
        table.addCell(metricCell("Rechazadas", String.valueOf(document.totalRejected()), WARNING_PANEL));
        table.addCell(metricCell("Sin respuesta", String.valueOf(document.unanswered()), EMPTY_PANEL));
        return table;
    }

    private Paragraph sectionTitle(String title) {
        Paragraph paragraph = new Paragraph(title, SECTION_FONT);
        paragraph.setSpacingBefore(6);
        paragraph.setSpacingAfter(10);
        return paragraph;
    }

    private PdfPTable errorDistributionTable(List<ReportPdfDocument.ErrorEntry> items) {
        if (items.isEmpty()) {
            return emptyState("No hay errores registrados para este periodo.");
        }

        PdfPTable table = new PdfPTable(new float[] {2.6f, 1.2f, 1.4f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.addCell(headerCell("Tipo de error"));
        table.addCell(headerCell("Cantidad"));
        table.addCell(headerCell("Porcentaje"));

        for (ReportPdfDocument.ErrorEntry item : items) {
            table.addCell(bodyCell(capitalize(item.label()), false));
            table.addCell(bodyCell(String.valueOf(item.count()), true));
            table.addCell(bodyCell(formatPercentage(item.percentage()), true));
        }
        return table;
    }

    private PdfPTable topWordsTable(List<ReportPdfDocument.TopWordEntry> items) {
        if (items.isEmpty()) {
            return emptyState("No hay palabras recurrentes para este mes.");
        }

        PdfPTable table = new PdfPTable(new float[] {2.3f, 1.6f, 1.0f, 1.2f, 1.1f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.addCell(headerCell("Palabra"));
        table.addCell(headerCell("Tipo"));
        table.addCell(headerCell("Frecuencia"));
        table.addCell(headerCell("Confianza"));
        table.addCell(headerCell("Aceptadas"));

        for (ReportPdfDocument.TopWordEntry item : items) {
            table.addCell(bodyCell(item.originalWord(), false));
            table.addCell(bodyCell(capitalize(item.errorType()), false));
            table.addCell(bodyCell(String.valueOf(item.frequency()), true));
            table.addCell(bodyCell(String.format(Locale.US, "%.2f", item.averageConfidence()), true));
            table.addCell(bodyCell(String.valueOf(item.acceptedCorrectionCount()), true));
        }
        return table;
    }

    private PdfPTable notesBlock(String notes) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        PdfPCell cell = panelCell(new Color(252, 252, 255));
        cell.setPadding(16);
        Paragraph title = new Paragraph("Observaciones del docente", CARD_LABEL_FONT);
        title.setSpacingAfter(8);
        cell.addElement(title);
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
        Paragraph paragraph = new Paragraph(message,
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, MUTED));
        cell.addElement(paragraph);
        table.addCell(cell);
        return table;
    }

    private PdfPCell metricCell(String label, String value, Color background) {
        PdfPCell cell = panelCell(background);
        cell.setPadding(14);
        Paragraph labelParagraph = new Paragraph(label, CARD_LABEL_FONT);
        labelParagraph.setSpacingAfter(8);
        cell.addElement(labelParagraph);
        cell.addElement(new Paragraph(value, CARD_VALUE_FONT));
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
        PdfPCell cell = new PdfPCell(new Phrase(text, centered ? BODY_BOLD_FONT : BODY_FONT));
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
        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, INK);
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setSpacingAfter(4);
        return paragraph;
    }

    private Paragraph detailParagraph(String text) {
        Paragraph paragraph = new Paragraph(text, BODY_FONT);
        paragraph.setLeading(14);
        return paragraph;
    }

    private String formatPercentage(double value) {
        return String.format(Locale.US, "%.2f%%", value);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
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
                        new Phrase("Seguimiento pedagogico y consolidado mensual",
                                FontFactory.getFont(FontFactory.HELVETICA, 11, MUTED)),
                        42, pageHeight - 96, 0);
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase("Mes: " + document.monthLabel(),
                                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK)),
                        42, pageHeight - 118, 0);

                PdfContentByte top = writer.getDirectContent();
                top.saveState();
                top.setColorFill(new Color(250, 250, 250));
                top.roundRectangle(pageWidth - 182, pageHeight - 124, 140, 58, 10);
                top.fill();
                top.setColorStroke(BORDER);
                top.roundRectangle(pageWidth - 182, pageHeight - 124, 140, 58, 10);
                top.stroke();
                top.restoreState();

                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase("Tasa de aceptacion", FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED)),
                        pageWidth - 166, pageHeight - 94, 0);
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase(String.format(Locale.US, "%.2f%%", document.acceptanceRatePercentage()),
                                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, INK)),
                        pageWidth - 166, pageHeight - 114, 0);
            }

            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase("Reporte mensual consolidado", FOOTER_BOLD_FONT), 42, 18, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Pagina " + writer.getPageNumber(), FOOTER_FONT), pageWidth - 42, 18, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(document.monthCode() + " - " + document.studentAlias(), SMALL_FONT), 42, 6, 0);
        }
    }
}
