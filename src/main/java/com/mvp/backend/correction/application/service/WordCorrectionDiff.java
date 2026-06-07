package com.mvp.backend.correction.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deriva las palabras corregidas comparando el texto original con la sugerencia
 * aceptada por el alumno. La IA ya no entrega el detalle palabra por palabra
 * (ver docs/arquitectura-integracion.md), por lo que el backend lo calcula con
 * un diff a nivel de palabra basado en la subsecuencia comun mas larga (LCS).
 *
 * Solo se reportan sustituciones (una palabra original reemplazada por otra).
 * Las inserciones y eliminaciones puras se ignoran porque no representan una
 * palabra que al alumno le cueste escribir.
 */
final class WordCorrectionDiff {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private WordCorrectionDiff() {
    }

    record Change(String originalWord, String correctedWord, int startPosition, int endPosition) {
    }

    private record Token(String text, int start, int end) {
    }

    static List<Change> between(String originalText, String correctedText) {
        List<Token> original = tokenize(originalText);
        List<String> corrected = tokenize(correctedText).stream().map(Token::text).toList();

        int m = original.size();
        int n = corrected.size();
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = original.get(i).text().equalsIgnoreCase(corrected.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Change> changes = new ArrayList<>();
        List<Token> pendingOriginal = new ArrayList<>();
        List<String> pendingCorrected = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (original.get(i).text().equalsIgnoreCase(corrected.get(j))) {
                flush(pendingOriginal, pendingCorrected, changes);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                pendingOriginal.add(original.get(i));
                i++;
            } else {
                pendingCorrected.add(corrected.get(j));
                j++;
            }
        }
        while (i < m) {
            pendingOriginal.add(original.get(i));
            i++;
        }
        while (j < n) {
            pendingCorrected.add(corrected.get(j));
            j++;
        }
        flush(pendingOriginal, pendingCorrected, changes);
        return changes;
    }

    private static void flush(List<Token> originalWords, List<String> correctedWords, List<Change> changes) {
        int pairs = Math.min(originalWords.size(), correctedWords.size());
        for (int k = 0; k < pairs; k++) {
            Token token = originalWords.get(k);
            changes.add(new Change(token.text(), correctedWords.get(k), token.start(), token.end()));
        }
        originalWords.clear();
        correctedWords.clear();
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return tokens;
    }
}
