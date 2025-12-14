package com.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${services.auth}")
    private String authBase;

    @Value("${services.user}")
    private String userBase;

    @Bean("authClient")
    public WebClient authClient() {
        return WebClient.builder().baseUrl(authBase).build();
    }

    @Bean("userClient")
    public WebClient userClient() {
        return WebClient.builder().baseUrl(userBase).build();
    }
}
