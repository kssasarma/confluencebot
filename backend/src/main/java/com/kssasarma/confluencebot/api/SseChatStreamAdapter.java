package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.ChatStreamEvent;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.ChatStreamHandle;
import com.kssasarma.confluencebot.chat.ChatStreamListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes a streamed answer to an {@link SseEmitter}.
 *
 * This is the only place that knows the answer travels over server-sent events; the chat service
 * just pushes into a {@link ChatStreamListener}.
 *
 * The adapter is also the disconnect detector: the browser closing the tab surfaces as a failed
 * write or an emitter callback, and either one cancels the generation instead of letting the model
 * keep producing tokens nobody will read.
 */
final class SseChatStreamAdapter implements ChatStreamListener {

    private static final Logger log = LoggerFactory.getLogger(SseChatStreamAdapter.class);

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ChatStreamHandle> handle = new AtomicReference<>(ChatStreamHandle.NOOP);

    SseChatStreamAdapter(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onTimeout(() -> {
            log.warn("Answer stream timed out; cancelling generation");
            abort();
            emitter.complete();
        });
        emitter.onError(error -> abort());
        emitter.onCompletion(this::abort);
    }

    /** Registers the in-flight generation so it can be cancelled when the client goes away. */
    void bind(ChatStreamHandle streamHandle) {
        handle.set(streamHandle);
        if (closed.get()) streamHandle.cancel();
    }

    @Override
    public void onSources(List<SourceReference> sources) {
        if (sources != null && !sources.isEmpty()) send(ChatStreamEvent.Sources.of(sources));
    }

    @Override
    public void onToken(String delta) {
        send(ChatStreamEvent.Token.of(delta));
    }

    @Override
    public void onCompleted(ChatApiResponse response) {
        send(ChatStreamEvent.Done.of(response));
        finish();
    }

    @Override
    public void onFailed(String message) {
        send(ChatStreamEvent.Failure.of(message));
        finish();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void send(ChatStreamEvent event) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // A write failure means the client is gone. Stop generating for a reader that left.
            log.debug("Answer stream closed by the client: {}", e.getMessage());
            abort();
        }
    }

    private void finish() {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().data(ChatStreamEvent.SENTINEL));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Could not close the answer stream cleanly: {}", e.getMessage());
            abort();
        }
    }

    /** Marks the stream dead and cancels generation. Safe to call repeatedly. */
    private void abort() {
        if (closed.compareAndSet(false, true)) handle.get().cancel();
    }
}
