package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.user.User;

/**
 * The half of single sign-on that has nothing to do with OAuth: what happens once the provider
 * agrees.
 */
public interface SsoService {

    /**
     * What the sign-in screen shows, and where its button goes. Safe to call unauthenticated.
     *
     * @param baseUrl this service's own externally-visible origin (scheme, host, port and any
     *                 proxy path prefix), so {@code authorizationUrl} is a URL the browser can
     *                 navigate to directly — there is no reverse proxy in front of this service
     *                 that also fronts the frontend, so a path-only URL would resolve against
     *                 whatever origin the frontend happens to be served from instead.
     */
    SsoStatusResponse describe(String baseUrl);

    /** Records a one-time code for this user and returns it. Only the hash is stored. */
    String issueLoginCode(User user);

    /** Redeems a code exactly once for a normal token pair. */
    AuthResponse exchangeLoginCode(String code);
}
