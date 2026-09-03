package com.kssasarma.confluencebot.user;

/**
 * Where an account came from.
 *
 * <p>Not the same question as "how does this person sign in". An account created here is
 * {@code LOCAL} and stays {@code LOCAL} even after it is linked to an OTDS identity and starts
 * signing in through it — including the bootstrap administrator, whose password login has to keep
 * working or a directory outage locks everyone out of the admin screen. {@code OTDS} marks an
 * account this service never issued a password for, and never will.
 */
public enum AuthProvider {

    /** Created in this application, with a password of its own. */
    LOCAL,

    /** Provisioned on first sign-in through OpenText Directory Services. Has no local password. */
    OTDS
}
