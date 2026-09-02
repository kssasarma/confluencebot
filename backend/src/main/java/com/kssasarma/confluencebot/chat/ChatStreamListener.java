package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Receives an answer as it is produced.
 *
 * <p>The chat pipeline pushes into this instead of returning a transport-specific type, so the
 * service layer stays unaware of server-sent events, WebSockets or whatever comes next.
 *
 * <p>Exactly one of {@link #onCompleted} or {@link #onFailed} is called. Both are terminal for the
 * <em>answer</em>; a transport may stay open a moment longer to deliver metadata that was still
 * being computed — see {@link #expect}.
 */
public interface ChatStreamListener {

    /** The cited pages, known before the first token is generated. */
    void onSources(List<SourceReference> sources);

    /** A fragment of the answer, ready to be appended to what the user already sees. */
    void onToken(String delta);

    /** The finished answer, including anything the transcript recorded. */
    void onCompleted(ChatApiResponse response);

    /** The answer could not be produced; the message is safe to show to the user. */
    void onFailed(String message);

    /**
     * Announces work that may still emit an event after the answer is complete.
     *
     * <p>Call this <em>before</em> {@link #onCompleted}. A transport that can stay open — SSE —
     * holds the connection until the stage settles, so a late event still reaches the reader.
     * One that cannot simply ignores it, which is why this is a default no-op.
     *
     * <p>The caller is responsible for bounding the stage; a listener will not wait forever.
     */
    default void expect(CompletionStage<?> pending) {
        // Transports that close as soon as the answer is complete have nothing to wait for.
    }

    /**
     * A better conversation title arrived after the answer.
     *
     * <p>Summarising an exchange costs a model call of its own and must never delay the answer, so
     * the clipped question ships with the answer and this replaces it if the summary lands while
     * the reader is still connected.
     */
    default void onTitleRefined(String chatId, String title) {
        // Not every transport carries out-of-band updates.
    }
}
