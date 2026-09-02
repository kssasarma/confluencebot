package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The payloads sent over the answer stream, one JSON object per server-sent event.
 *
 * Every payload carries a {@code type} so a client can switch on it, and the stream always ends
 * with either a {@code done} or an {@code error} payload followed by the literal {@code [DONE]}.
 */
@Schema(description = "One event of a streamed answer")
public sealed interface ChatStreamEvent {

    String SENTINEL = "[DONE]";

    String type();

    /** The cited pages, sent before the first token. */
    record Sources(String type, List<SourceReference> sources) implements ChatStreamEvent {
        public static Sources of(List<SourceReference> sources) {
            return new Sources("sources", sources);
        }
    }

    /** A fragment of the answer, to be appended to what is already on screen. */
    record Token(String type, String delta) implements ChatStreamEvent {
        public static Token of(String delta) {
            return new Token("token", delta);
        }
    }

    /** The answer finished: carries the conversation it was recorded in and the follow-ups. */
    record Done(String type, String chatId, String title, List<String> followUpQuestions)
            implements ChatStreamEvent {
        public static Done of(ChatApiResponse response) {
            return new Done("done", response.chatId(), response.title(), response.followUpQuestions());
        }
    }

    /** The answer could not be produced; the message is safe to show to the user. */
    record Failure(String type, String message) implements ChatStreamEvent {
        public static Failure of(String message) {
            return new Failure("error", message);
        }
    }
}
