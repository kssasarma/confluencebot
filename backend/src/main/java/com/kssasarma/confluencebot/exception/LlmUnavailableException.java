package com.kssasarma.confluencebot.exception;

/** The language model could not be reached, or refused the call (circuit open, bulkhead full). */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
