package com.kssasarma.confluencebot.user.dto;

import com.kssasarma.confluencebot.api.dto.Citation;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A persisted turn of a conversation.
 *
 * <p>Carries the same grounding metadata as a live answer, so a reloaded transcript renders
 * identically to the one the user just watched arrive — citations still resolve to links, and the
 * match strength is still shown.
 */
@Schema(description = "A persisted turn of a conversation")
public record ChatMessageResponse(
        Long id,
        @Schema(description = "USER or ASSISTANT") String role,
        String content,
        List<SourceReference> sources,
        List<String> followUpQuestions,

        @Schema(description = "Resolves each bracketed marker in the content to the page it cites")
        List<Citation> citations,

        @Schema(description = "How well retrieval matched the question, 0–1. Null for turns "
                + "recorded before the score existed, and for user messages.")
        Double confidence,

        Instant createdAt
) {

    public ChatMessageResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        followUpQuestions = followUpQuestions == null ? List.of() : List.copyOf(followUpQuestions);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
