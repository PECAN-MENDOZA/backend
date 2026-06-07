package com.mvp.backend.correction.application.dto;

import java.util.List;
import java.util.stream.IntStream;

public record CorrectionSuggestionResponse(
        String text,
        boolean recommended) {

    public static List<CorrectionSuggestionResponse> from(List<String> suggestions) {
        return IntStream.range(0, suggestions.size())
                .mapToObj(index -> new CorrectionSuggestionResponse(
                        suggestions.get(index),
                        index == 0))
                .toList();
    }
}
