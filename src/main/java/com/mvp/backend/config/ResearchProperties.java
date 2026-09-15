package com.mvp.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametros del modulo de investigacion.
 *
 * <ul>
 *   <li>{@code accessCodeTtl}: vigencia de un codigo de acceso sin canjear.</li>
 *   <li>{@code ppmNonInferiorityMargin}: margen maximo tolerable de reduccion de PPM (δPPM, en palabras por
 *       minuto). Ausente por defecto: PPM se informa como descriptivo hasta que el margen se justifique.
 *       Si esta presente debe ser finito y mayor que 0.</li>
 *   <li>{@code tasLimit}: limite maximo aceptable de TAS (porcentaje). Ausente por defecto: TAS se informa
 *       como descriptivo hasta que el limite se defina con el piloto y los especialistas. Si esta presente
 *       debe ser finito y estar en [0, 100].</li>
 * </ul>
 *
 * <p>Un umbral invalido hace fallar el arranque: un error de configuracion nunca debe convertir un resultado
 * descriptivo en un criterio con un umbral sin sentido metodologico.
 */
@ConfigurationProperties(prefix = "app.research")
public record ResearchProperties(Duration accessCodeTtl, Double ppmNonInferiorityMargin, Double tasLimit) {

    public ResearchProperties {
        if (!validPpmMargin(ppmNonInferiorityMargin)) {
            throw new IllegalArgumentException(
                    "app.research.ppm-non-inferiority-margin must be a finite number greater than 0 (was "
                            + ppmNonInferiorityMargin + ")");
        }
        if (!validTasLimit(tasLimit)) {
            throw new IllegalArgumentException(
                    "app.research.tas-limit must be a finite percentage between 0 and 100 (was " + tasLimit + ")");
        }
    }

    /** Ausente, o finito y estrictamente positivo. */
    public static boolean validPpmMargin(Double margin) {
        return margin == null || (Double.isFinite(margin) && margin > 0.0);
    }

    /** Ausente, o finito y dentro de [0, 100]. */
    public static boolean validTasLimit(Double limit) {
        return limit == null || (Double.isFinite(limit) && limit >= 0.0 && limit <= 100.0);
    }
}
