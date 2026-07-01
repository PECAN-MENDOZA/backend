package com.mvp.backend.correction.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mvp.backend.correction.domain.model.ErrorType;

class WordErrorClassifierTests {

    @ParameterizedTest
    @CsvSource({
            "jugo,     jugó,    TILDE",
            "cancion,  canción, TILDE",
            "dola,     bola,    DISLEXIA_VISUAL",
            "qeso,     peso,    DISLEXIA_VISUAL",
            "oy,       hoy,     H_MUDA",
            "aora,     ahora,   H_MUDA",
            "bamos,    vamos,   CONFUSION_B_V",
            "tubo,     tuvo,    CONFUSION_B_V",
            "kasa,     casa,    C_Q_K",
            "kiero,    quiero,  C_Q_K",
            "caro,     carro,   LETRA_DOBLE",
            "pero,     perro,   LETRA_DOBLE",
            "casa,     gato,    OTRO"
    })
    void classifiesKnownPairs(String original, String corrected, ErrorType expected) {
        assertThat(WordErrorClassifier.classify(original, corrected)).isEqualTo(expected);
    }

    @Test
    void tildePrioritizedOverOtherRules() {
        assertThat(WordErrorClassifier.classify("arbol", "árbol")).isEqualTo(ErrorType.TILDE);
    }

    @Test
    void identicalWordsAreOther() {
        assertThat(WordErrorClassifier.classify("mesa", "mesa")).isEqualTo(ErrorType.OTRO);
    }
}
