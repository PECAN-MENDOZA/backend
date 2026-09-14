package com.mvp.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parametros del modulo de investigacion; {@code accessCodeTtl} es la vigencia de un codigo de acceso sin canjear. */
@ConfigurationProperties(prefix = "app.research")
public record ResearchProperties(Duration accessCodeTtl) {
}
