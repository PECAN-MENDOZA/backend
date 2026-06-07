package com.mvp.backend.correction.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class StubAiCorrectionClientTests {

    private final StubAiCorrectionClient client = new StubAiCorrectionClient(new ObjectMapper());

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
}
