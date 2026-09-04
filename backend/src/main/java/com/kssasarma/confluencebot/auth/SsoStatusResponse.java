package com.kssasarma.confluencebot.auth;

/**
 * What the sign-in screen needs to know before anyone has authenticated.
 *
 * <p>Deliberately says nothing about the provider beyond its name and where to start: the client
 * id, the issuer and the endpoints are this service's business, and a signed-out visitor asking
 * how to sign in is not owed them.
 *
 * <p>{@code enabled} false is the honest answer for a deployment that signs in with passwords
 * alone, and the sign-in screen renders its password form either way — this decides whether a
 * second button appears beside it, never whether the first one does.
 */
public record SsoStatusResponse(
        boolean enabled,
        /** The configured provider id. Identifies which provider a session came from. Null when off. */
        String providerId,
        String providerName,
        /** Where to send the browser to begin. Null when SSO is off. */
        String authorizationUrl,
        /** Where to send it after signing out, to end the session at the provider too. Optional. */
        String logoutUrl
) {
    public static SsoStatusResponse disabled() {
        return new SsoStatusResponse(false, null, null, null, null);
    }
}
