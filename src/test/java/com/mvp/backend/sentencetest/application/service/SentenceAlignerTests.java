package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mvp.backend.sentencetest.application.service.SentenceAligner.Alignment;

class SentenceAlignerTests {

    @Test
    void tokenizesStrippingEdgePunctuationOnly() {
        assertThat(SentenceAligner.tokenize("¡Hola, mundo! ¿Qué tal… «bien»?"))
                .containsExactly("Hola", "mundo", "Qué", "tal", "bien");
        assertThat(SentenceAligner.tokenize("  ")).isEmpty();
        assertThat(SentenceAligner.tokenize("a-ver co'mo")).containsExactly("a-ver", "co'mo");
    }

    @Test
    void identicalTextsHaveZeroErrors() {
        Alignment a = SentenceAligner.align("El perro corre por el parque.", "El perro corre por el parque");
        assertThat(a.wordCount()).isEqualTo(6);
        assertThat(a.errorCount()).isZero();
        assertThat(a.edits()).isEmpty();
    }

    @Test
    void substitutionOmissionInsertionCountOneEach() {
        Alignment a = SentenceAligner.align("El perro corre por el parque", "El pero corre el parque grande");
        assertThat(a.errorCount()).isEqualTo(3);
        assertThat(a.edits()).extracting(SentenceAligner.Edit::type).containsExactly("SUSTITUCION", "OMISION", "INSERCION");
        assertThat(a.edits().get(0)).isEqualTo(new SentenceAligner.Edit("SUSTITUCION", "perro", "pero", 2));
        assertThat(a.edits().get(1)).isEqualTo(new SentenceAligner.Edit("OMISION", "por", null, 4));
        assertThat(a.edits().get(2)).isEqualTo(new SentenceAligner.Edit("INSERCION", null, "grande", 7));
    }

    @Test
    void accentsCountButCapitalsDoNot() {
        // Tildes: sí (Él/El, está/esta, aquí/aqui).
        assertThat(SentenceAligner.align("Él está aquí.", "El esta aqui").errorCount()).isEqualTo(3);
        // Mayúsculas: no (la IA no capitaliza; contarlas sesgaba la condición con ayuda).
        assertThat(SentenceAligner.align("María canta.", "maría canta").errorCount()).isZero();
        assertThat(SentenceAligner.align("El perro de mi tía se cayó en el río.", "el perro de mi tía se cayó en el río")
                .errorCount()).isZero();
        // La ñ sigue contando aunque cambie la mayúscula: Mañana / manana.
        assertThat(SentenceAligner.align("Mañana voy.", "manana voy").errorCount()).isEqualTo(1);
    }

    @Test
    void segmentationIgnoresCapitals() {
        assertThat(SentenceAligner.align("A ver si vienes.", "aver si vienes").errorCount()).isEqualTo(1);
        assertThat(SentenceAligner.align("También vino.", "tam bién vino").errorCount()).isEqualTo(1);
    }

    @Test
    void segmentationErrorsCountOne() {
        Alignment union = SentenceAligner.align("Voy a ver la tele", "Voy aver la tele");
        assertThat(union.errorCount()).isEqualTo(1);
        assertThat(union.edits()).containsExactly(new SentenceAligner.Edit("UNION", "a ver", "aver", 2));

        Alignment split = SentenceAligner.align("Quiero también ir", "Quiero tam bién ir");
        assertThat(split.errorCount()).isEqualTo(1);
        assertThat(split.edits()).containsExactly(new SentenceAligner.Edit("SEPARACION", "también", "tam bién", 2));
    }

    @Test
    void emptyWrittenTextCountsEveryWordAsOmitted() {
        Alignment a = SentenceAligner.align("Uno dos tres", "");
        assertThat(a.errorCount()).isEqualTo(3);
        assertThat(a.edits()).allMatch(e -> e.type().equals("OMISION"));
    }

    @Test
    void jsonIsStableAndEscaped() {
        Alignment a = SentenceAligner.align("Dijo \"hola\"", "Dijo \"ola\"");
        assertThat(a.toJson()).isEqualTo(
                "{\"word_count\":2,\"error_count\":1,\"edits\":[{\"type\":\"SUSTITUCION\",\"expected\":\"hola\",\"written\":\"ola\",\"position\":2}],\"incidents\":[]}");
        assertThat(SentenceAligner.align("a", "a").toJson()).isEqualTo("{\"word_count\":1,\"error_count\":0,\"edits\":[],\"incidents\":[]}");
    }
}
