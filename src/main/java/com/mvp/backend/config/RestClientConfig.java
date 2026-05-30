package com.mvp.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient aiRestClient(AiProperties properties) {
        return RestClient.builder().baseUrl(properties.baseUrl()).build();
    }
}
