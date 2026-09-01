package com.kssasarma.confluencebot.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "confluence")
public record ConfluenceProperties(
        @NotBlank String baseUrl,
        @NotBlank String pat,
        @NotBlank String spaceKey,
        @Positive int pageFetchLimit,
        @Positive int requestTimeoutSeconds
) {}
