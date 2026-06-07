package com.mvp.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String mode, String baseUrl, String correctionPath, String feedbackPath) {
}
