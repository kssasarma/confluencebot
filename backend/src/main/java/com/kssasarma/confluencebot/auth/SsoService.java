package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.user.User;

/** The half of single sign-on that has nothing to do with OAuth: what happens once OTDS agrees. */
public interface SsoService {

    /** What the sign-in screen shows, and where its button goes. Safe to call unauthenticated. */
    SsoStatusResponse describe();

    /** Records a one-time code for this user and returns it. Only the hash is stored. */
    String issueLoginCode(User user);

    /** Redeems a code exactly once for a normal token pair. */
    AuthResponse exchangeLoginCode(String code);
}
