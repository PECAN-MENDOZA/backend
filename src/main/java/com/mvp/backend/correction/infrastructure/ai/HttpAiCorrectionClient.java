package com.mvp.backend.correction.infrastructure.ai;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.mvp.backend.config.AiProperties;
import com.mvp.backend.shared.exception.AiServiceException;

@Component
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "http")
public class HttpAiCorrectionClient implements AiCorrectionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiCorrectionClient.class);

    private final RestClient restClient;
    private final AiProperties properties;

    public HttpAiCorrectionClient(RestClient aiRestClient, AiProperties properties) {
        this.restClient = aiRestClient;
        this.properties = properties;
    }

    @Override
    public AiCorrectionResponse correct(String originalText, UUID studentId) {
        try {
            return restClient.post()
                    .uri(properties.correctionPath())
                    .body(new AiCorrectionRequest(originalText, studentId))
                    .retrieve()
                    .body(AiCorrectionResponse.class);
        } catch (RestClientException exception) {
            throw new AiServiceException("AI correction service is unavailable", exception);
        }
    }

    @Override
    public void sendFeedback(UUID studentId, String originalText, String selectedSuggestion, boolean accepted) {
        try {
            restClient.post()
                    .uri(properties.feedbackPath())
                    .body(new AiFeedbackRequest(studentId, originalText, selectedSuggestion, accepted))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            // Best-effort: la indisponibilidad de la IA no debe romper el registro del feedback del alumno.
            log.warn("Could not deliver correction feedback to the AI service", exception);
        }
    }
}
