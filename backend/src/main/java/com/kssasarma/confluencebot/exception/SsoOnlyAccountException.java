package com.kssasarma.confluencebot.exception;

import org.springframework.security.core.AuthenticationException;

/** A password was offered for an account that must sign in through SSO now. */
public class SsoOnlyAccountException extends AuthenticationException {
    public SsoOnlyAccountException(String message) {
        super(message);
    }
}
