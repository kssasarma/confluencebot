package com.kssasarma.confluencebot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "chat.retrieval")
public record ChatRetrievalProperties(
        @Positive int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold
) {}
