package com.mvp.backend.research.application.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.mvp.backend.shared.exception.BusinessException;

/**
 * Codificador y lector RFC 4180 minimos (sin dependencia externa). Exporta con salto {@code \n};
 * al leer acepta {@code \n} y {@code \r\n}, campos entrecomillados con {@code ""} y saltos internos,
 * un BOM inicial opcional, y rechaza UTF-8 invalido y NUL incrustados.
 */
final class AnnotationCsv {

    private static final char BOM = '\uFEFF';

    private AnnotationCsv() {
    }

    static String encode(List<String> header, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder();
        appendRow(csv, header);
        rows.forEach(row -> appendRow(csv, row));
        return csv.toString();
    }

    private static void appendRow(StringBuilder csv, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(row.get(i)));
        }
        csv.append('\n');
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** Decodifica UTF-8 estricto, quita el BOM y rechaza NUL antes de parsear. */
    static String decode(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new BusinessException("File is not valid UTF-8");
        }
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        if (text.indexOf('\u0000') >= 0) {
            throw new BusinessException("File contains an embedded NUL character");
        }
        if (text.isBlank()) {
            throw new BusinessException("File is empty");
        }
        return text;
    }

    /** Filas (incluida la cabecera) como listas de campos; las lineas vacias finales se ignoran. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    i++;
                    if (i < length && text.charAt(i) != ',' && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                        throw new BusinessException("Malformed quoted field at row " + (rows.size() + 1));
                    }
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (!field.isEmpty()) {
                        throw new BusinessException("Unexpected quote in row " + (rows.size() + 1));
                    }
                    quoted = true;
                    i++;
                }
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    i++;
                }
                case '\r', '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    i += c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n' ? 2 : 1;
                }
                default -> {
                    field.append(c);
                    i++;
                }
            }
        }
        if (quoted) {
            throw new BusinessException("Unterminated quoted field at row " + (rows.size() + 1));
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        rows.removeIf(r -> r.size() == 1 && r.get(0).isEmpty());
        return rows;
    }
}
