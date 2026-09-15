package com.mvp.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    // La IA tiene un cold start de ~70s (arranque + carga del modelo) cuando estuvo inactiva.
    // El read timeout debe permitir esa primera llamada lenta sin abortarla; una correccion
    // con la IA caliente responde en ~1s. El connect timeout corto detecta que la IA no
    // acepta conexiones; el read timeout (90s por defecto) cubre el cold start pero sigue por
    // debajo del limite de Cloud Run (~300s) para no colgarse indefinidamente. Ambos se ajustan
    // con app.ai.connect-timeout / app.ai.read-timeout.
    // NOTA: para que el teclado reciba esa primera correccion, la app movil tambien debe tener
    // un readTimeout > cold start (~70s); si la app corta antes, fallara igual.
    @Bean
    RestClient aiRestClient(AiProperties properties) {
        Duration connectTimeout = requirePositive("app.ai.connect-timeout", properties.connectTimeout());
        Duration readTimeout = requirePositive("app.ai.read-timeout", properties.readTimeout());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private static Duration requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be a positive duration");
        }
        return value;
    }
}
