package com.mvp.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cliente HTTP de la IA. Los timeouts acotan toda llamada: el de conexion (5 s) detecta que la IA no
 * acepta conexiones; el de lectura (90 s) cubre el cold start (~70 s) sin colgar indefinidamente.
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String mode,
        String baseUrl,
        String correctionPath,
        String feedbackPath,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout) {
}
