package com.kssasarma.confluencebot.user.dto;

import com.kssasarma.confluencebot.user.ChatSession;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A conversation belonging to the authenticated user")
public record ChatSessionResponse(
        @Schema(description = "Client-visible conversation identifier (UUID)") String chatId,
        @Schema(description = "Title, derived from the first question until the user renames it") String title,
        boolean pinned,
        @Schema(description = "Number of persisted turns; 0 means the conversation is still empty") long messageCount,
        Instant createdAt,
        Instant updatedAt,

        @Schema(description = "True while the title is machine-derived. A client can use this to "
                + "animate a title that improves itself moments after the first answer.")
        boolean titleGenerated,

        @Schema(description = "Present only in search results: the passage that matched.")
        ChatMessageMatch match
) {

    public static ChatSessionResponse from(ChatSession session, long messageCount) {
        return from(session, messageCount, null);
    }

    public static ChatSessionResponse from(ChatSession session, long messageCount, ChatMessageMatch match) {
        return new ChatSessionResponse(
                session.getChatId(), session.getTitle(), session.isPinned(),
                messageCount, session.getCreatedAt(), session.getUpdatedAt(),
                session.isTitleGenerated(), match);
    }
}
