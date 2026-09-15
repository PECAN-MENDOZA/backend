package com.mvp.backend.correction.application.service;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Lista de sugerencias tal como se mostraron al alumno: el texto corregido primero, luego las
 * alternativas, sin duplicados (comparados sin espacios en los extremos) y como maximo
 * {@link #MAX_SUGGESTIONS}. Es la unica definicion de "sugerencia ofrecida": la usan la correccion
 * (respuesta y validacion del feedback) y la anotacion ciega (indice de la sugerencia evaluada).
 */
public final class OfferedSuggestions {

    public static final int MAX_SUGGESTIONS = 3;

    private OfferedSuggestions() {
    }

    public static List<String> of(String correctedText, List<String> alternatives) {
        var unique = new LinkedHashMap<String, String>();
        add(unique, correctedText);
        if (alternatives != null) {
            alternatives.forEach(alternative -> add(unique, alternative));
        }
        return unique.values().stream().limit(MAX_SUGGESTIONS).toList();
    }

    private static void add(LinkedHashMap<String, String> suggestions, String suggestion) {
        if (suggestion != null && !suggestion.isBlank()) {
            suggestions.putIfAbsent(suggestion.strip(), suggestion);
        }
    }
}
