package com.kssasarma.confluencebot.auth;

/**
 * What the sign-in screen needs to know before anyone has authenticated.
 *
 * <p>Deliberately says nothing about the provider beyond its name and where to start: the client
 * id, the issuer and the endpoints are this service's business, and a signed-out visitor asking
 * how to sign in is not owed them.
 */
public record SsoStatusResponse(
        boolean enabled,
        String providerName,
        /** Where to send the browser to begin. Null when SSO is off. */
        String authorizationUrl,
        /** Where to send it after signing out, to end the session at the provider too. Optional. */
        String logoutUrl
) {
    public static SsoStatusResponse disabled() {
        return new SsoStatusResponse(false, null, null, null);
    }
}
