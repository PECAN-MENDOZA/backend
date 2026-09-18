package com.mvp.backend.sentencetest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mvp.backend.sentencetest.application.service.SentenceAligner;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AutoErrorDetail;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.student.domain.model.Student;

class AutoErrorDetailTests {

    private static final String ALIGNMENT =
            "{\"word_count\":2,\"error_count\":1,\"edits\":[{\"type\":\"SUSTITUCION\",\"expected\":\"hola\",\"written\":\"ola\",\"position\":2}],\"incidents\":[]}";

    @Test
    void nullDetailBecomesAnObjectWithTheIncident() {
        assertThat(AutoErrorDetail.withIncident(null, "MODEL_VERSION_CHANGED"))
                .isEqualTo("{\"incidents\":[\"MODEL_VERSION_CHANGED\"]}");
        assertThat(AutoErrorDetail.incidents(null)).isEmpty();
    }

    @Test
    void incidentIsAppendedToTheAlignerDetailWithoutTouchingTheRest() {
        String once = AutoErrorDetail.withIncident(ALIGNMENT, "MODEL_VERSION_CHANGED");
        String twice = AutoErrorDetail.withIncident(once, "MODEL_VERSION_CHANGED");

        assertThat(once).isEqualTo(ALIGNMENT.replace("\"incidents\":[]", "\"incidents\":[\"MODEL_VERSION_CHANGED\"]"));
        assertThat(AutoErrorDetail.incidents(twice)).containsExactly("MODEL_VERSION_CHANGED", "MODEL_VERSION_CHANGED");
    }

    @Test
    void finishingADictatedSentenceKeepsTheIncidentsRecordedWhileItWasOpen() {
        var test = new SentenceTest("PRUEBA-01", "Dictado", UUID.randomUUID());
        var student = new Student("alumno", "UPC", "hash");
        var attempt = new TestAttempt(test, student, "app", "backend", Instant.parse("2026-09-17T10:00:00Z"));
        var sentence = new TestSentence(test, 1, SentenceKind.DICTATED, "hola mundo", Assistance.ASSISTED);
        var response = new TestResponse(attempt, sentence, Instant.parse("2026-09-17T10:00:00Z"));
        response.appendDetail(AutoErrorDetail.withIncident(response.getAutoErrorDetail(), "MODEL_VERSION_CHANGED"));

        SentenceAligner.Alignment alignment = SentenceAligner.align("hola mundo", "ola mundo");
        response.recordAutoErrors(alignment.errorCount(), alignment.toJson());

        assertThat(response.getAutoErrorCount()).isEqualTo(1);
        assertThat(response.getAutoErrorDetail())
                .isEqualTo(alignment.toJson().replace("\"incidents\":[]", "\"incidents\":[\"MODEL_VERSION_CHANGED\"]"));
    }
}
