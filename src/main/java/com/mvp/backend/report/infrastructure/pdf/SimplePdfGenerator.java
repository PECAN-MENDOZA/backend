package com.mvp.backend.report.infrastructure.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.mvp.backend.shared.exception.PdfGenerationException;

@Component
public class SimplePdfGenerator {

    private static final Charset PDF_CHARSET = Charset.forName("windows-1252");
    private static final int MAX_LINES_PER_PAGE = 44;

    public byte[] generate(List<String> rawLines) {
        try {
            List<String> wrappedLines = wrapLines(rawLines, 92);
            List<List<String>> pages = paginate(wrappedLines, MAX_LINES_PER_PAGE);
            return buildPdf(pages);
        } catch (Exception exception) {
            throw new PdfGenerationException("Failed to generate PDF report", exception);
        }
    }

    private byte[] buildPdf(List<List<String>> pages) throws Exception {
        List<byte[]> objects = new ArrayList<>();
        int fontObjectNumber = 3 + (pages.size() * 2);
        StringBuilder kids = new StringBuilder();

        objects.add(encode("<< /Type /Catalog /Pages 2 0 R >>"));
        for (int i = 0; i < pages.size(); i++) {
            int pageObjectNumber = 3 + (i * 2);
            kids.append(pageObjectNumber).append(" 0 R ");
        }
        objects.add(encode("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>"));

        for (int i = 0; i < pages.size(); i++) {
            int pageObjectNumber = 3 + (i * 2);
            int contentObjectNumber = pageObjectNumber + 1;
            objects.add(encode("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Resources << /Font << /F1 " + fontObjectNumber + " 0 R >> >> "
                    + "/Contents " + contentObjectNumber + " 0 R >>"));

            String stream = contentStream(pages.get(i));
            byte[] streamBytes = encode(stream);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            content.write(encode("<< /Length " + streamBytes.length + " >>\nstream\n"));
            content.write(streamBytes);
            content.write(encode("\nendstream"));
            objects.add(content.toByteArray());
        }

        objects.add(encode("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write(encode("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n"));

        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.size());
            pdf.write(encode((i + 1) + " 0 obj\n"));
            pdf.write(objects.get(i));
            pdf.write(encode("\nendobj\n"));
        }

        int xrefOffset = pdf.size();
        pdf.write(encode("xref\n0 " + (objects.size() + 1) + "\n"));
        pdf.write(encode("0000000000 65535 f \n"));
        for (int i = 1; i < offsets.size(); i++) {
            pdf.write(encode(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.get(i))));
        }
        pdf.write(encode("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\n"));
        pdf.write(encode("startxref\n" + xrefOffset + "\n%%EOF"));
        return pdf.toByteArray();
    }

    private String contentStream(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        builder.append("BT\n");
        builder.append("/F1 11 Tf\n");
        builder.append("1 0 0 1 50 760 Tm\n");
        builder.append("14 TL\n");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append("T*\n");
            }
            builder.append("(").append(escape(lines.get(i))).append(") Tj\n");
        }
        builder.append("ET");
        return builder.toString();
    }

    private List<String> wrapLines(List<String> rawLines, int maxWidth) {
        List<String> wrapped = new ArrayList<>();
        for (String rawLine : rawLines) {
            String line = sanitize(rawLine);
            if (line.isBlank()) {
                wrapped.add("");
                continue;
            }
            String[] words = line.split("\\s+");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                if (currentLine.isEmpty()) {
                    currentLine.append(word);
                } else if (currentLine.length() + 1 + word.length() <= maxWidth) {
                    currentLine.append(' ').append(word);
                } else {
                    wrapped.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                }
            }
            if (!currentLine.isEmpty()) {
                wrapped.add(currentLine.toString());
            }
        }
        return wrapped;
    }

    private List<List<String>> paginate(List<String> lines, int linesPerPage) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += linesPerPage) {
            int end = Math.min(lines.size(), i + linesPerPage);
            pages.add(new ArrayList<>(lines.subList(i, end)));
        }
        if (pages.isEmpty()) {
            pages.add(List.of("No data available"));
        }
        return pages;
    }

    private String sanitize(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-')
                .replace('\r', ' ')
                .replace('\n', ' ');
        CharsetEncoder encoder = PDF_CHARSET.newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPLACE);
        encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
        StringBuilder sanitized = new StringBuilder(normalized.length());
        for (char character : normalized.toCharArray()) {
            if (encoder.canEncode(character)) {
                sanitized.append(character);
            } else {
                sanitized.append('?');
            }
        }
        return sanitized.toString().trim();
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private byte[] encode(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
