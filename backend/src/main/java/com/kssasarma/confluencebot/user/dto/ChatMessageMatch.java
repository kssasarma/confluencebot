package com.kssasarma.confluencebot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why a conversation came back from a search.
 *
 * <p>Matches are delimited with {@code [[HL]]}…{@code [[/HL]]} rather than {@code <mark>} tags on
 * purpose: the snippet is user-written content, and a client that had to render it as HTML to see
 * the highlight would be one careless {@code dangerouslySetInnerHTML} away from executing whatever
 * somebody pasted into a chat. Plain delimiters can be split and wrapped safely.
 */
@Schema(description = "The passage that matched a conversation search")
public record ChatMessageMatch(
        @Schema(description = "Message the snippet came from. Null when only the title matched.")
        Long messageId,

        @Schema(description = "Extract around the match, with hits wrapped in [[HL]]…[[/HL]]",
                example = "The [[HL]]deploy[[/HL]] pipeline runs the smoke suite before promoting")
        String snippet
) {

    public static final String HIGHLIGHT_OPEN = "[[HL]]";
    public static final String HIGHLIGHT_CLOSE = "[[/HL]]";

    /** A conversation whose title matched but which has no passage to quote. */
    public static ChatMessageMatch titleOnly() {
        return new ChatMessageMatch(null, null);
    }
}
