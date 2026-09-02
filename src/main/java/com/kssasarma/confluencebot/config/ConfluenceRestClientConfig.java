package com.kssasarma.confluencebot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class ConfluenceRestClientConfig {

    @Bean("confluenceRestClient")
    public RestClient confluenceRestClient(RestClient.Builder builder, ConfluenceProperties props) {
        // Use the auto-configured RestClient.Builder so that Spring Boot's HttpMessageConverter
        // chain (including the application's @Primary ObjectMapper) is in effect.
        // RestClient.builder() (static) bypasses those converters and falls back to stock Jackson.
        return builder
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.pat())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
