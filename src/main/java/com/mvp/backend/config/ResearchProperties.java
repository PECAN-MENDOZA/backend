package com.mvp.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametros del modulo de investigacion.
 *
 * <ul>
 *   <li>{@code accessCodeTtl}: vigencia de un codigo de acceso sin canjear.</li>
 *   <li>{@code ppmNonInferiorityMargin}: margen maximo tolerable de reduccion de PPM (δPPM, en palabras por
 *       minuto). Ausente por defecto: PPM se informa como descriptivo hasta que el margen se justifique.</li>
 *   <li>{@code tasLimit}: limite maximo aceptable de TAS (porcentaje). Ausente por defecto: TAS se informa
 *       como descriptivo hasta que el limite se defina con el piloto y los especialistas.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.research")
public record ResearchProperties(Duration accessCodeTtl, Double ppmNonInferiorityMargin, Double tasLimit) {
}
