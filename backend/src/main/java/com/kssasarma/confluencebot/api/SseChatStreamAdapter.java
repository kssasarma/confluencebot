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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes a streamed answer to an {@link SseEmitter}.
 *
 * <p>This is the only place that knows the answer travels over server-sent events; the chat service
 * just pushes into a {@link ChatStreamListener}.
 *
 * <p>It carries three responsibilities that only make sense at the transport edge:
 *
 * <ul>
 *   <li><b>Disconnect detection.</b> The browser closing the tab surfaces as a failed write or an
 *       emitter callback; either one cancels generation instead of letting the model keep producing
 *       tokens nobody will read.</li>
 *   <li><b>Keep-alive.</b> A comment frame every few seconds stops a reverse proxy or load
 *       balancer from treating a slow generation as an idle connection and closing it mid-answer.
 *       Comments are invisible to {@code EventSource} and to the hand-rolled reader alike.</li>
 *   <li><b>A bounded linger.</b> When the pipeline announces late-arriving metadata through
 *       {@link #expect}, the connection stays open until that work settles or the grace period
 *       elapses — whichever comes first. Nothing blocks a thread while it waits.</li>
 * </ul>
 *
 * <p>Sends are serialised: {@link SseEmitter} is not thread-safe and, once a linger is in play,
 * the answer thread and the metadata callback are genuinely different threads.
 */
final class SseChatStreamAdapter implements ChatStreamListener {

    private static final Logger log = LoggerFactory.getLogger(SseChatStreamAdapter.class);

    private final SseEmitter emitter;
    private final ScheduledExecutorService scheduler;
    private final Duration lingerGrace;

    private final ReentrantLock sendLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicReference<ChatStreamHandle> handle = new AtomicReference<>(ChatStreamHandle.NOOP);
    private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
    private final AtomicReference<CompletionStage<?>> pending = new AtomicReference<>();

    SseChatStreamAdapter(SseEmitter emitter, ScheduledExecutorService scheduler,
                         Duration heartbeatInterval, Duration lingerGrace) {
        this.emitter = emitter;
        this.scheduler = scheduler;
        this.lingerGrace = lingerGrace;

        emitter.onTimeout(() -> {
            log.warn("Answer stream timed out; cancelling generation");
            abort();
            emitter.complete();
        });
        emitter.onError(error -> abort());
        emitter.onCompletion(this::abort);

        startHeartbeat(heartbeatInterval);
    }

    /** Registers the in-flight generation so it can be cancelled when the client goes away. */
    void bind(ChatStreamHandle streamHandle) {
        handle.set(streamHandle);
        if (closed.get()) streamHandle.cancel();
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    @Override
    public void onSources(List<SourceReference> sources) {
        if (sources != null && !sources.isEmpty()) send(ChatStreamEvent.Sources.of(sources));
    }

    @Override
    public void onToken(String delta) {
        send(ChatStreamEvent.Token.of(delta));
    }

    @Override
    public void expect(CompletionStage<?> work) {
        pending.set(work);
    }

    @Override
    public void onTitleRefined(String chatId, String title) {
        if (chatId != null && title != null && !title.isBlank()) {
            send(ChatStreamEvent.Title.of(chatId, title));
        }
    }

    @Override
    public void onCompleted(ChatApiResponse response) {
        send(ChatStreamEvent.Done.of(response));
        finishWhenSettled();
    }

    @Override
    public void onFailed(String message) {
        send(ChatStreamEvent.Failure.of(message));
        finish();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Holds the connection open only while announced metadata is genuinely outstanding.
     *
     * <p>A conversation that has nothing pending closes immediately, exactly as before — the
     * linger is paid for by the one turn per conversation that can produce a refined title, never
     * by every turn.
     */
    private void finishWhenSettled() {
        CompletionStage<?> work = pending.getAndSet(null);
        if (work == null) {
            finish();
            return;
        }

        ScheduledFuture<?> deadline = scheduler.schedule(
                this::finish, lingerGrace.toMillis(), TimeUnit.MILLISECONDS);

        work.whenComplete((ignored, error) -> {
            deadline.cancel(false);
            finish();
        });
    }

    /**
     * Sends a comment frame periodically so intermediaries see traffic on a slow generation.
     *
     * <p>Answers routinely spend ten seconds in retrieval and re-ranking before the first token,
     * and a proxy with a short idle timeout closes the connection in that window. The client then
     * sees a transport error on an answer that was about to arrive.
     */
    private void startHeartbeat(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) return;

        long millis = interval.toMillis();
        heartbeat.set(scheduler.scheduleWithFixedDelay(this::ping, millis, millis, TimeUnit.MILLISECONDS));
    }

    private void ping() {
        if (closed.get()) return;
        sendLock.lock();
        try {
            if (closed.get()) return;
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (Exception e) {
            log.debug("Keep-alive failed; the client has gone: {}", e.getMessage());
            abort();
        } finally {
            sendLock.unlock();
        }
    }

    private void send(ChatStreamEvent event) {
        if (closed.get()) return;
        sendLock.lock();
        try {
            if (closed.get()) return;
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // A write failure means the client is gone. Stop generating for a reader that left.
            log.debug("Answer stream closed by the client: {}", e.getMessage());
            abort();
        } finally {
            sendLock.unlock();
        }
    }

    /** Sends the sentinel and closes. Idempotent: the linger can race its own deadline. */
    private void finish() {
        if (!finished.compareAndSet(false, true)) return;
        stopHeartbeat();
        if (closed.get()) return;

        sendLock.lock();
        try {
            if (closed.get()) return;
            emitter.send(SseEmitter.event().data(ChatStreamEvent.SENTINEL));
            emitter.complete();
        } catch (Exception e) {
            log.debug("Could not close the answer stream cleanly: {}", e.getMessage());
            abort();
        } finally {
            sendLock.unlock();
        }
    }

    /** Marks the stream dead and cancels generation. Safe to call repeatedly. */
    private void abort() {
        if (closed.compareAndSet(false, true)) {
            stopHeartbeat();
            handle.get().cancel();
        }
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> scheduled = heartbeat.getAndSet(null);
        if (scheduled != null) scheduled.cancel(false);
    }
}
