package com.kssasarma.confluencebot.exception;

public class DuplicateIngestionJobException extends RuntimeException {
    public DuplicateIngestionJobException(String message) {
        super(message);
    }
}
