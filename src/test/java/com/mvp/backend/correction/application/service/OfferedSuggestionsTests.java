package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OfferedSuggestionsTests {

    @Test
    void noChangesWhenEveryOfferedTextEqualsTheOriginal() {
        assertThat(OfferedSuggestions.noChanges("Hola mamá", "Hola mamá", "[\"Hola mamá\"]")).isTrue();
        assertThat(OfferedSuggestions.noChanges("Hola mamá", "Hola mamá", "[]")).isTrue();
        assertThat(OfferedSuggestions.noChanges("Hola mamá", null, "[\"Hola mamá\"]")).isTrue();
    }

    @Test
    void anyDifferentSuggestionMeansThereWereChanges() {
        assertThat(OfferedSuggestions.noChanges("ola mama", "Hola mamá", "[\"Hola mamá\"]")).isFalse();
        // Una alternativa distinta basta, aunque el texto corregido sea igual al original.
        assertThat(OfferedSuggestions.noChanges("Hola mama", "Hola mama", "[\"Hola mama\",\"Hola mamá\"]")).isFalse();
        // Comparacion exacta, como el teclado: un espacio de mas ya es un cambio.
        assertThat(OfferedSuggestions.noChanges("Hola mamá ", "Hola mamá", "[]")).isFalse();
    }

    @Test
    void withoutAnyStoredSuggestionItCannotBeNoChanges() {
        assertThat(OfferedSuggestions.noChanges("Hola", null, null)).isFalse();
        assertThat(OfferedSuggestions.noChanges("Hola", null, "[]")).isFalse();
        assertThat(OfferedSuggestions.noChanges("Hola", "", "not json")).isFalse();
    }
}
