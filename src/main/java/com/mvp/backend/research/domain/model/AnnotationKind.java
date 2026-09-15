package com.mvp.backend.research.domain.model;

import java.util.List;

/** Tipo de lote ciego: conteo ortografico por texto final o seguridad semantica (0-2) por sugerencia. */
public enum AnnotationKind {

    ORTHOGRAPHY(List.of("sample_code", "text", "score")),
    SEMANTIC(List.of("sample_code", "original_text", "suggestion", "score"));

    private final List<String> columns;

    AnnotationKind(List<String> columns) {
        this.columns = columns;
    }

    /** Cabecera del CSV exportado; la columna {@code score} viaja vacia para que la complete el evaluador. */
    public List<String> columns() {
        return columns;
    }

    /** Valida un puntaje ya parseado segun la escala del lote. */
    public boolean accepts(int score) {
        return switch (this) {
            case ORTHOGRAPHY -> score >= 0;
            case SEMANTIC -> score >= 0 && score <= 2;
        };
    }

    public String scoreRule() {
        return switch (this) {
            case ORTHOGRAPHY -> "a non-negative integer (words with at least one orthographic error)";
            case SEMANTIC -> "0, 1 or 2 (semantic safety scale)";
        };
    }
}
