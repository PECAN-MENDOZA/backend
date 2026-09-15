package com.mvp.backend.correction.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class StubAiCorrectionClientTests {

    private final StubAiCorrectionClient client = new StubAiCorrectionClient();
    private final Logger logger = (Logger) LoggerFactory.getLogger(StubAiCorrectionClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    @Test
    void correctEchoesOriginalTextAndKeepsStudent() {
        UUID studentId = UUID.randomUUID();

        AiCorrectionResponse response = client.correct("mi mama me dijo que baya", studentId);

        assertThat(response.studentId()).isEqualTo(studentId);
        assertThat(response.correctedText()).isEqualTo("mi mama me dijo que baya");
        assertThat(response.processingTimeMs()).isPositive();
    }

    @Test
    void correctIncludesOriginalTextAmongSuggestions() {
        AiCorrectionResponse response = client.correct("la ora del recreo", UUID.randomUUID());

        assertThat(response.suggestions()).contains("la ora del recreo");
        assertThat(response.suggestions()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void sendFeedbackNeverThrows() {
        assertThatCode(() -> client.sendFeedback(
                UUID.randomUUID(), "mi lapis se callo", "mi lapiz se cayo", true))
                .doesNotThrowAnyException();
    }

    @Test
    void neverLogsTheStudentText() {
        UUID studentId = UUID.randomUUID();

        client.correct("mi mama me dijo que baya", studentId);
        client.sendFeedback(studentId, "mi lapis se callo", "mi lapiz se cayo", true);

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).allSatisfy(message -> assertThat(message)
                .doesNotContain("baya", "lapis", "lapiz", studentId.toString()));
    }
}
