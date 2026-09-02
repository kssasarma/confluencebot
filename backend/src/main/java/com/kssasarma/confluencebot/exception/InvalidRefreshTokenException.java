package com.kssasarma.confluencebot.exception;

/** The presented refresh token is unknown, already used, revoked or expired. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
