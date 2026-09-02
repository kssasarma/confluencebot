package com.kssasarma.confluencebot.exception;

/** The requested resource does not exist, or does not belong to the caller. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
