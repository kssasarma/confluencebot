package com.kssasarma.confluencebot.chat;

/** Lets the caller abandon an in-flight answer, for instance when the client disconnects. */
@FunctionalInterface
public interface ChatStreamHandle {

    /** Nothing left to cancel — the answer was already delivered synchronously. */
    ChatStreamHandle NOOP = () -> { };

    void cancel();
}
