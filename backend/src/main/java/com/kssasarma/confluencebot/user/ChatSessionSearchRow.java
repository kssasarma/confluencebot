package com.kssasarma.confluencebot.user;

import java.time.Instant;

/**
 * One conversation returned by a search, together with the passage that matched.
 *
 * <p>A projection rather than the entity: the snippet is computed by the database and belongs to
 * the query, not to the conversation, so hanging it off {@link ChatSession} would put a transient
 * field on a mapped entity for the benefit of one endpoint.
 */
public interface ChatSessionSearchRow {

    Long getId();

    String getChatId();

    String getTitle();

    Boolean getPinned();

    Instant getCreatedAt();

    Instant getUpdatedAt();

    /** The message the snippet came from, or null when only the title matched. */
    Long getMatchMessageId();

    /** Highlighted extract, with matches wrapped in the agreed delimiters. Null on a title match. */
    String getSnippet();
}
