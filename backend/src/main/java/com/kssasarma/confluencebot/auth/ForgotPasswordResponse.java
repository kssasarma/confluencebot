package com.kssasarma.confluencebot.auth;

/**
 * @param emailSent whether a reset code actually reached an inbox — never whether the email is
 *                  registered, which this deliberately does not reveal. {@code false} means mail
 *                  is down or misconfigured; the reader's recourse then is the same one that
 *                  covers a new user's welcome email — ask an admin to re-share a temporary
 *                  password from Settings, which works independently of the mail relay.
 */
public record ForgotPasswordResponse(boolean emailSent) {}
