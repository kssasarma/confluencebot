package com.kssasarma.confluencebot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of conversations plus the bookmark for the next.
 *
 * <p>{@code nextCursor} is null when the page is the last one, which is the only signal a client
 * needs: "keep asking until the cursor is null" has no off-by-one and no total to keep accurate
 * while rows are being written underneath it.
 */
@Schema(description = "A page of the user's conversations")
public record ChatSessionPage(

        @Schema(description = "Conversations in this page, pinned first then most recently used")
        List<ChatSessionResponse> items,

        @Schema(description = "Opaque bookmark for the next page. Null when there are no more.")
        String nextCursor
) {

    public ChatSessionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static ChatSessionPage last(List<ChatSessionResponse> items) {
        return new ChatSessionPage(items, null);
    }
}
