package com.mvp.backend.correction.application.service;

import java.util.LinkedHashMap;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lista de sugerencias tal como se mostraron al alumno: el texto corregido primero, luego las
 * alternativas, sin duplicados (comparados sin espacios en los extremos) y como maximo
 * {@link #MAX_SUGGESTIONS}. Es la unica definicion de "sugerencia ofrecida": la usan la correccion
 * (respuesta y validacion del feedback) y la anotacion ciega (indice de la sugerencia evaluada).
 */
public final class OfferedSuggestions {

    public static final int MAX_SUGGESTIONS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

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

    /**
     * "Ya estaba bien": la IA no ofrecio ningun texto distinto del original. Se reconstruye la lista
     * ofrecida desde corrected_text y suggestions_json (como la guarda CorrectionService) y se compara
     * cada sugerencia con el original de forma exacta, como el teclado. Sin ninguna sugerencia guardada
     * no se puede afirmar y devuelve false; un JSON ilegible cuenta como lista vacia.
     */
    public static boolean noChanges(String originalText, String correctedText, String suggestionsJson) {
        List<String> offered = of(correctedText, readJson(suggestionsJson));
        return !offered.isEmpty() && offered.stream().allMatch(suggestion -> suggestion.equals(originalText));
    }

    private static List<String> readJson(String suggestionsJson) {
        if (suggestionsJson == null || suggestionsJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(suggestionsJson, STRING_LIST);
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private static void add(LinkedHashMap<String, String> suggestions, String suggestion) {
        if (suggestion != null && !suggestion.isBlank()) {
            suggestions.putIfAbsent(suggestion.strip(), suggestion);
        }
    }
}
