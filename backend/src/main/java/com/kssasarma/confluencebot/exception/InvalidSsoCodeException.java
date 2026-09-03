package com.kssasarma.confluencebot.exception;

/** The one-time code from an OTDS sign-in was unknown, already redeemed, or past its minute. */
public class InvalidSsoCodeException extends RuntimeException {
    public InvalidSsoCodeException(String message) {
        super(message);
    }
}
