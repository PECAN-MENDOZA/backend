package com.mvp.backend.insights.application.dto;

import java.util.List;
import java.util.UUID;

import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown.WordPair;

/** Errores de un alumno en un periodo: por tipo con ejemplos, y las palabras que repitio (para practicar). */
public record StudentErrorsResponse(
        UUID studentId,
        String from,
        String to,
        long total,
        List<ErrorTypeExamples> types,
        List<WordPair> practiceWords) {

    public record ErrorTypeExamples(String type, String label, long count, List<WordPair> examples) {
    }
}
