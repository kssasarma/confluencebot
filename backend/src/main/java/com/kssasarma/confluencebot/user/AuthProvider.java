package com.kssasarma.confluencebot.user;

/**
 * Where an account came from.
 *
 * <p>Not the same question as "how does this person sign in". An account created here is
 * {@code LOCAL} and stays {@code LOCAL} even after it is linked to a directory identity and starts
 * signing in through it — including the bootstrap administrator, whose password login has to keep
 * working or a directory outage locks everyone out of the admin screen. {@code SSO} marks an
 * account this service never issued a password for, and never will.
 *
 * <p>Which directory that was is a separate field: see {@link User#getSsoProviderId()}. Two values
 * rather than one because the answer to "was there ever a password here" outlives any particular
 * provider a deployment happens to be pointed at.
 */
public enum AuthProvider {

    /** Created in this application, with a password of its own. */
    LOCAL,

    /** Provisioned on first sign-in through an identity provider. Has no local password. */
    SSO
}
