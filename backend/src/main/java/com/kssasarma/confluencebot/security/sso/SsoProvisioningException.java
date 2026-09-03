package com.kssasarma.confluencebot.security.sso;

/**
 * The directory authenticated somebody this application cannot seat.
 *
 * <p>Distinct from an OAuth failure on purpose: the handshake worked, the token is valid, and the
 * message is one the person can act on — a missing claim, a disabled account, an address already
 * spoken for — so it is the one shown on the sign-in screen rather than a generic failure.
 */
public class SsoProvisioningException extends RuntimeException {
    public SsoProvisioningException(String message) {
        super(message);
    }
}
