package com.mvp.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    // Si la IA se cuelga, el backend debe fallar rapido (AiServiceException) en vez de
    // esperar el timeout de Cloud Run (~300s). Una correccion sana responde en ~1-2s, asi
    // que estos margenes no afectan el flujo normal.
    private static final Duration AI_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    RestClient aiRestClient(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(AI_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(AI_READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
