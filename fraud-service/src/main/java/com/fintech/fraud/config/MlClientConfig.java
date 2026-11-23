package com.fintech.fraud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Spring RestClient configuration for Python ML service communication.
 * Enforces explicit connection and read timeouts to prevent thread starvation under latency.
 */
@Configuration
public class MlClientConfig {

    @Value("${fraud.ml-service.url:http://localhost:8000}")
    private String mlServiceUrl;

    @Value("${fraud.ml-service.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${fraud.ml-service.read-timeout-ms:2000}")
    private int readTimeoutMs;

    @Bean
    public RestClient mlRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(mlServiceUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
