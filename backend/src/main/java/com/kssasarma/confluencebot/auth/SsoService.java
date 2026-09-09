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
     * @param requestBaseUrl this service's own origin as read off the current request (scheme,
     *                 host, port and any proxy path prefix), used to make {@code authorizationUrl}
     *                 a URL the browser can navigate to directly — there is no reverse proxy in
     *                 front of this service that also fronts the frontend, so a path-only URL
     *                 would resolve against whatever origin the frontend happens to be served from
     *                 instead. Only a fallback: {@code app.sso.public-base-url}, when configured,
     *                 is used instead, since this value is only as trustworthy as the
     *                 {@code X-Forwarded-*} headers of every proxy between the browser and here.
     */
    SsoStatusResponse describe(String requestBaseUrl);

    /** Records a one-time code for this user and returns it. Only the hash is stored. */
    String issueLoginCode(User user);

    /** Redeems a code exactly once for a normal token pair. */
    AuthResponse exchangeLoginCode(String code);
}
