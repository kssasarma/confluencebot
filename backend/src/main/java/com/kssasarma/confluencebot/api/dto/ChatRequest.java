package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Incoming chat query")
public record ChatRequest(
        @Schema(description = "Natural-language question to answer from Confluence documentation",
                example = "How do I reset my password?",
                minLength = 3, maxLength = 1000)
        @NotBlank(message = "Query must not be blank")
        @Size(min = 3, max = 1000, message = "Query must be 3–1000 characters")
        String question,

        @Schema(description = "Conversation this question belongs to. A UUID minted by the client: "
                + "the conversation is created on the first question, so an abandoned draft never "
                + "reaches the database. Omit it to ask without recording a transcript.",
                example = "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "chatId must be a UUID")
        String chatId
) {}
