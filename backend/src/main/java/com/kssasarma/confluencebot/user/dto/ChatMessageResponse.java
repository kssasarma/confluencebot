package com.kssasarma.confluencebot.user.dto;

import com.kssasarma.confluencebot.api.dto.SourceReference;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "A persisted turn of a conversation")
public record ChatMessageResponse(
        Long id,
        @Schema(description = "USER or ASSISTANT") String role,
        String content,
        List<SourceReference> sources,
        List<String> followUpQuestions,
        Instant createdAt
) {}
