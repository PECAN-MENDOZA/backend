package com.mvp.backend.correction.domain.service;

import java.text.Normalizer;

import com.mvp.backend.correction.domain.model.ErrorType;

/**
 * Clasifica un par (palabra escrita por el alumno, palabra corregida) en un
 * ErrorType usando heurísticas ortográficas. Es puro y sin estado: la IA no
 * participa. Las reglas se evalúan de la más específica a la más general.
 */
public final class WordErrorClassifier {

    private WordErrorClassifier() {
    }

    public static ErrorType classify(String original, String corrected) {
        if (original == null || corrected == null) {
            return ErrorType.OTRO;
        }
        String o = original.toLowerCase();
        String c = corrected.toLowerCase();
        if (o.equals(c)) {
            return ErrorType.OTRO;
        }
        if (stripAccents(o).equals(stripAccents(c))) {
            return ErrorType.TILDE;
        }
        if (collapseVisual(o).equals(collapseVisual(c))) {
            return ErrorType.DISLEXIA_VISUAL;
        }
        if (o.replace("h", "").equals(c.replace("h", ""))) {
            return ErrorType.H_MUDA;
        }
        if (o.replace('v', 'b').equals(c.replace('v', 'b'))) {
            return ErrorType.CONFUSION_B_V;
        }
        if (hardC(o).equals(hardC(c))) {
            return ErrorType.C_Q_K;
        }
        if (collapseDoubles(o).equals(collapseDoubles(c))) {
            return ErrorType.LETRA_DOBLE;
        }
        return ErrorType.OTRO;
    }

    private static String stripAccents(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static String collapseVisual(String s) {
        return s.replace('d', 'b').replace('q', 'p').replace('w', 'm').replace('u', 'n');
    }

    private static String hardC(String s) {
        return s.replace("qu", "k").replace('c', 'k').replace('q', 'k');
    }

    private static String collapseDoubles(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (i == 0 || ch != s.charAt(i - 1)) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
