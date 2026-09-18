package com.mvp.backend.insights.application.service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mvp.backend.correction.domain.model.ErrorType;
import com.mvp.backend.correction.domain.service.WordErrorClassifier;
import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown.WordPair;

/** Cuenta pares (palabra escrita, palabra corregida) y los agrupa por tipo de error. */
final class WordPairTally {

    /** Un tipo de error con su total y sus pares mas repetidos. */
    record TypeCount(ErrorType type, long count, List<WordPair> examples) {
    }

    private record WordKey(String original, String corrected) {
    }

    private static final Comparator<WordPair> MOST_REPEATED_FIRST = Comparator.comparingLong(WordPair::count).reversed()
            .thenComparing(WordPair::original)
            .thenComparing(WordPair::corrected);

    private final EnumMap<ErrorType, Map<WordKey, Long>> byType = new EnumMap<>(ErrorType.class);
    private long total;

    /** Acumula las filas [.., original, corrected] de una consulta de pares; las dos ultimas columnas son las palabras. */
    static WordPairTally of(List<Object[]> rows) {
        WordPairTally tally = new WordPairTally();
        for (Object[] row : rows) {
            tally.add((String) row[row.length - 2], (String) row[row.length - 1]);
        }
        return tally;
    }

    void add(String original, String corrected) {
        ErrorType type = WordErrorClassifier.classify(original, corrected);
        byType.computeIfAbsent(type, t -> new HashMap<>()).merge(new WordKey(original, corrected), 1L, Long::sum);
        total++;
    }

    long total() {
        return total;
    }

    /** Tipos por cantidad desc; dentro de cada tipo, hasta examplesPerType pares (cuenta desc, luego palabra). */
    List<TypeCount> byType(int examplesPerType) {
        return byType.entrySet().stream()
                .map(entry -> new TypeCount(
                        entry.getKey(),
                        entry.getValue().values().stream().mapToLong(Long::longValue).sum(),
                        pairs(entry.getValue()).sorted(MOST_REPEATED_FIRST).limit(examplesPerType).toList()))
                .sorted(Comparator.comparingLong(TypeCount::count).reversed())
                .toList();
    }

    /** Pares de cualquier tipo con al menos minCount repeticiones, los mas repetidos primero, hasta limit. */
    List<WordPair> repeated(int minCount, int limit) {
        return byType.values().stream()
                .flatMap(WordPairTally::pairs)
                .filter(pair -> pair.count() >= minCount)
                .sorted(MOST_REPEATED_FIRST)
                .limit(limit)
                .toList();
    }

    private static Stream<WordPair> pairs(Map<WordKey, Long> counts) {
        return counts.entrySet().stream()
                .map(entry -> new WordPair(entry.getKey().original(), entry.getKey().corrected(), entry.getValue()));
    }
}
