package com.kssasarma.confluencebot.exception;

public class ConfluenceException extends RuntimeException {
    public ConfluenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
