package com.mvp.backend.correction.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;

class AiCorrectionResponseTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsCamelCaseModelVersionFromTheAiContract() {
        var response = objectMapper.readValue("""
                {"studentId":"%s","correctedText":"hola","processingTimeMs":12,
                 "suggestions":["hola"],"modelVersion":"beto-lora-1.2"}
                """.formatted(UUID.randomUUID()), AiCorrectionResponse.class);

        assertThat(response.modelVersion()).isEqualTo("beto-lora-1.2");
    }

    @Test
    void acceptsSnakeCaseModelVersionAsAlias() {
        var response = objectMapper.readValue("""
                {"studentId":"%s","correctedText":"hola","processingTimeMs":12,
                 "suggestions":["hola"],"model_version":"beto-lora-1.2"}
                """.formatted(UUID.randomUUID()), AiCorrectionResponse.class);

        assertThat(response.modelVersion()).isEqualTo("beto-lora-1.2");
    }

    @Test
    void modelVersionIsOptionalForTheCurrentAi() {
        var response = objectMapper.readValue("""
                {"studentId":"%s","correctedText":"hola","processingTimeMs":12,"suggestions":["hola"]}
                """.formatted(UUID.randomUUID()), AiCorrectionResponse.class);

        assertThat(response.modelVersion()).isNull();
    }

    @Test
    void correctionRequestReadsTheOptionalRunId() {
        UUID runId = UUID.randomUUID();

        var withRun = objectMapper.readValue(
                "{\"texto_original\":\"hola\",\"id_ejecucion\":\"" + runId + "\"}", ProcessCorrectionRequest.class);
        var withoutRun = objectMapper.readValue("{\"texto_original\":\"hola\"}", ProcessCorrectionRequest.class);

        assertThat(withRun.experimentRunId()).isEqualTo(runId);
        assertThat(withoutRun.experimentRunId()).isNull();
    }
}
