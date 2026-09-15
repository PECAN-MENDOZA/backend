package com.mvp.backend.shared.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regla unica de tokenizacion de palabras del proyecto: una palabra es una secuencia de letras o digitos
 * Unicode, con apostrofes o guiones internos ("l'amour", "re-hacer" cuentan una vez); la puntuacion y los
 * simbolos no cuentan. La comparten PEO/PPM (resultados), la cota del puntaje ortografico (anotacion) y la
 * verosimilitud de la duracion al completar una ejecucion, de modo que todas cuentan lo mismo.
 */
public final class WordTokenizer {

    public static final Pattern WORD = Pattern.compile("[^\\W_]+(?:['’\\-][^\\W_]+)*", Pattern.UNICODE_CHARACTER_CLASS);

    private WordTokenizer() {
    }

    public static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
