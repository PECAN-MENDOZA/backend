package com.mvp.backend.correction.infrastructure.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.mvp.backend.config.AiProperties;
import com.mvp.backend.shared.exception.AiServiceException;

@Component
public class HttpAiCorrectionClient implements AiCorrectionClient {

    private final RestClient restClient;
    private final AiProperties properties;

    public HttpAiCorrectionClient(RestClient aiRestClient, AiProperties properties) {
        this.restClient = aiRestClient;
        this.properties = properties;
    }

    @Override
    public AiCorrectionResponse correct(String originalText, String additionalContext) {
        try {
            return restClient.post()
                    .uri(properties.correctionPath())
                    .body(new AiCorrectionRequest(originalText, additionalContext))
                    .retrieve()
                    .body(AiCorrectionResponse.class);
        } catch (RestClientException exception) {
            throw new AiServiceException("AI correction service is unavailable", exception);
        }
    }
}
