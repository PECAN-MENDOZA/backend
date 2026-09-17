package com.mvp.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Umbrales de las pruebas de oraciones: minSample = alumnos completados bajo los cuales la muestra es insuficiente. */
@ConfigurationProperties(prefix = "app.tests")
public record TestsProperties(@DefaultValue("8") int minSample) {
}
