package com.kssasarma.confluencebot.user;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The position of the last row of a page, so the next page can start exactly after it.
 *
 * <p>Keyset rather than offset pagination. An offset re-counts and discards every row before the
 * page on each request, so the sidebar of a heavy user gets slower the further they scroll; worse,
 * a conversation that moves to the top between requests shifts every offset by one and the reader
 * silently loses a row. A cursor is stable under concurrent writes and costs one index seek.
 *
 * <p>Encoded rather than exposed as three query parameters: the ordering is an implementation
 * detail, and a client that cannot construct a cursor cannot come to depend on its shape. It is
 * not a security boundary — everything in it is already visible to the caller — so it is plainly
 * Base64 rather than signed.
 *
 * @param pinned    whether the last row was pinned; pinned rows sort first
 * @param updatedAt when it last changed
 * @param id        its surrogate key, breaking ties between rows updated in the same millisecond
 */
public record ChatSessionCursor(boolean pinned, Instant updatedAt, long id) {

    private static final String SEPARATOR = "|";
    private static final int FIELD_COUNT = 3;

    public String encode() {
        String raw = (pinned ? "1" : "0") + SEPARATOR + updatedAt.toEpochMilli() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor supplied by a client.
     *
     * <p>An unreadable cursor yields empty rather than an error: a stale bookmark or a truncated
     * URL should start the list from the top, not fail the request.
     */
    public static Optional<ChatSessionCursor> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return Optional.empty();

        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\" + SEPARATOR);
            if (parts.length != FIELD_COUNT) return Optional.empty();

            return Optional.of(new ChatSessionCursor(
                    "1".equals(parts[0]),
                    Instant.ofEpochMilli(Long.parseLong(parts[1])),
                    Long.parseLong(parts[2])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * A bookmark pointing at this conversation, or empty when it has no key to point with.
     *
     * A row without a surrogate key has not been written yet, so there is nothing after it to
     * resume from — treating that as "no further pages" is the honest answer, and it keeps a
     * read path from throwing over a value it cannot use.
     */
    public static Optional<ChatSessionCursor> of(ChatSession session) {
        if (session.getId() == null || session.getUpdatedAt() == null) return Optional.empty();
        return Optional.of(
                new ChatSessionCursor(session.isPinned(), session.getUpdatedAt(), session.getId()));
    }
}
