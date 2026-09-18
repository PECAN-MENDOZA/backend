package com.mvp.backend.sentencetest.application.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Alineacion palabra a palabra por programacion dinamica entre la referencia dictada y lo escrito.
 * Coste 1 por sustitucion, omision, insercion, union de dos palabras en una (aver) o separacion de
 * una palabra en dos (tam bien). Tokens exactos: tildes y mayusculas cuentan; la puntuacion de los
 * bordes no. Misma semantica que exact_token_edits_v1 de evaluate.py (IA).
 */
public final class SentenceAligner {

    public record Edit(String type, String expected, String written, int position) {
    }

    public record Alignment(int wordCount, int errorCount, List<Edit> edits) {
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"word_count\":").append(wordCount)
              .append(",\"error_count\":").append(errorCount)
              .append(",\"edits\":[");
            for (int i = 0; i < edits.size(); i++) {
                Edit e = edits.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"type\":\"").append(e.type()).append("\",\"expected\":").append(jsonString(e.expected()))
                  .append(",\"written\":").append(jsonString(e.written()))
                  .append(",\"position\":").append(e.position()).append('}');
            }
            sb.append("],\"incidents\":[]}");
            return sb.toString();
        }

        private static String jsonString(String value) {
            if (value == null) return "null";
            StringBuilder sb = new StringBuilder("\"");
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            return sb.append('"').toString();
        }
    }

    private static final String EDGE_PUNCTUATION = ".,;:!?¿¡\"«»()-…'’";

    private SentenceAligner() {
    }

    public static List<String> tokenize(String text) {
        if (text == null) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String raw : text.trim().split("\\s+")) {
            String token = stripEdges(raw);
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    private static String stripEdges(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && EDGE_PUNCTUATION.indexOf(raw.charAt(start)) >= 0) start++;
        while (end > start && EDGE_PUNCTUATION.indexOf(raw.charAt(end - 1)) >= 0) end--;
        return raw.substring(start, end);
    }

    public static Alignment align(String reference, String written) {
        List<String> r = tokenize(reference);
        List<String> w = tokenize(written);
        int n = r.size();
        int m = w.size();
        int[][] cost = new int[n + 1][m + 1];
        // op[i][j]: 0 match, 1 sust, 2 omision (ref i sin escrito), 3 insercion (escrito j sin ref),
        // 4 union (r[i-2..i-1] -> w[j-1]), 5 separacion (r[i-1] -> w[j-2..j-1])
        // Orden de preferencia en empate (de mas a menos preferido): match/sust, omision, insercion,
        // union, separacion. Esto hace determinista la traza cuando varias operaciones cuestan igual.
        int[][] op = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) { cost[i][0] = i; op[i][0] = 2; }
        for (int j = 1; j <= m; j++) { cost[0][j] = j; op[0][j] = 3; }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                boolean same = r.get(i - 1).equals(w.get(j - 1));
                int best = cost[i - 1][j - 1] + (same ? 0 : 1);
                int bestOp = same ? 0 : 1;
                if (cost[i - 1][j] + 1 < best) { best = cost[i - 1][j] + 1; bestOp = 2; }
                if (cost[i][j - 1] + 1 < best) { best = cost[i][j - 1] + 1; bestOp = 3; }
                if (i >= 2 && (r.get(i - 2) + r.get(i - 1)).equals(w.get(j - 1)) && cost[i - 2][j - 1] + 1 < best) {
                    best = cost[i - 2][j - 1] + 1; bestOp = 4;
                }
                if (j >= 2 && r.get(i - 1).equals(w.get(j - 2) + w.get(j - 1)) && cost[i - 1][j - 2] + 1 < best) {
                    best = cost[i - 1][j - 2] + 1; bestOp = 5;
                }
                cost[i][j] = best;
                op[i][j] = bestOp;
            }
        }
        List<Edit> edits = new ArrayList<>();
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            switch (op[i][j]) {
                case 0 -> { i--; j--; }
                case 1 -> { edits.add(new Edit("SUSTITUCION", r.get(i - 1), w.get(j - 1), i)); i--; j--; }
                case 2 -> { edits.add(new Edit("OMISION", r.get(i - 1), null, i)); i--; }
                case 3 -> { edits.add(new Edit("INSERCION", null, w.get(j - 1), i + 1)); j--; }
                case 4 -> { edits.add(new Edit("UNION", r.get(i - 2) + " " + r.get(i - 1), w.get(j - 1), i - 1)); i -= 2; j--; }
                default -> { edits.add(new Edit("SEPARACION", r.get(i - 1), w.get(j - 2) + " " + w.get(j - 1), i)); i--; j -= 2; }
            }
        }
        Collections.reverse(edits);
        return new Alignment(n, cost[n][m], List.copyOf(edits));
    }
}
