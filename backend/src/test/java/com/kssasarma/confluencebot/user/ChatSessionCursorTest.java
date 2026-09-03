package com.kssasarma.confluencebot.user;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionCursorTest {

    private static final Instant WHEN = Instant.parse("2026-03-12T09:15:30.123Z");

    @Test
    void survivesARoundTrip() {
        ChatSessionCursor cursor = new ChatSessionCursor(true, WHEN, 4242L);

        assertThat(ChatSessionCursor.decode(cursor.encode())).contains(cursor);
    }

    @Test
    void encodesToSomethingUrlSafe() {
        String encoded = new ChatSessionCursor(false, WHEN, 1L).encode();

        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    /**
     * A stale bookmark or a truncated URL should start the list from the top, not fail the
     * request — the caller has asked for conversations, not for a lecture about their cursor.
     */
    @Test
    void unreadableCursorsAreIgnoredRatherThanRejected() {
        assertThat(ChatSessionCursor.decode(null)).isEmpty();
        assertThat(ChatSessionCursor.decode("")).isEmpty();
        assertThat(ChatSessionCursor.decode("   ")).isEmpty();
        assertThat(ChatSessionCursor.decode("not-base64!!")).isEmpty();
        assertThat(ChatSessionCursor.decode("YWJj")).isEmpty();            // decodes, wrong shape
        assertThat(ChatSessionCursor.decode("MXxub3R8bnVt")).isEmpty();    // right shape, not numeric
    }

    @Test
    void hasNoBookmarkForAnUnpersistedConversation() {
        ChatSession unsaved = new ChatSession();
        unsaved.setChatId("0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c");

        assertThat(ChatSessionCursor.of(unsaved)).isEqualTo(Optional.empty());
    }
}
