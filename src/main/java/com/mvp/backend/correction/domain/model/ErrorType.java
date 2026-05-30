package com.mvp.backend.correction.domain.model;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ErrorType {
    SPELLING,
    PHONOLOGICAL,
    SEMANTIC,
    NONE;

    @JsonCreator
    public static ErrorType fromValue(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "ortografico", "spelling" -> SPELLING;
            case "fonologico", "phonological" -> PHONOLOGICAL;
            case "semantico", "semantic" -> SEMANTIC;
            case "ninguno", "none" -> NONE;
            default -> throw new IllegalArgumentException("Unknown error type: " + value);
        };
    }

    @JsonValue
    public String toValue() {
        return switch (this) {
            case SPELLING -> "ortografico";
            case PHONOLOGICAL -> "fonologico";
            case SEMANTIC -> "semantico";
            case NONE -> "ninguno";
        };
    }
}
