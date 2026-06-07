package com.mvp.backend.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        String issuer,
        Duration tokenTtl,
        String jwtSecret,
        String encryptionKeyBase64,
        List<String> allowedOrigins,
        boolean allowInsecureDefaults) {
}
