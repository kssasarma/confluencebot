package com.kssasarma.confluencebot.api.dto;

import java.util.List;

public record ChatApiResponse(
        String answer,
        List<SourceReference> sources
) {
    public static ChatApiResponse noContext() {
        return new ChatApiResponse(
                "I could not find relevant information in the Confluence documentation for your question.",
                List.of()
        );
    }
}
