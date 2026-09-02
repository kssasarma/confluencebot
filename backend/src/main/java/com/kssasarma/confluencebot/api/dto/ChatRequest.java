package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Incoming chat query")
public record ChatRequest(
        @Schema(description = "Natural-language question to answer from Confluence documentation",
                example = "How do I reset my password?",
                minLength = 3, maxLength = 1000)
        @NotBlank(message = "Query must not be blank")
        @Size(min = 3, max = 1000, message = "Query must be 3–1000 characters")
        String query
) {}
