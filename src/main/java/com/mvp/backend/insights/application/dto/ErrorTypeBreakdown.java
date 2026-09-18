package com.mvp.backend.insights.application.dto;

import java.util.List;

/** Cuantas palabras corregidas caen en un tipo de error y cuales se repiten mas. */
public record ErrorTypeBreakdown(String type, String label, long count, List<WordPair> topWords) {

    public record WordPair(String original, String corrected, long count) {
    }
}
