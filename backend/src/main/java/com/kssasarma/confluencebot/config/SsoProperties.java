package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.user.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Single sign-on against OpenText Directory Services (OTDS).
 *
 * <p>OTDS speaks OAuth 2.0 / OpenID Connect as an authorization server, so it is wired up here as
 * a standard OIDC provider rather than through anything OpenText-specific. Two ways to describe
 * it, because OTDS deployments differ in whether they publish provider metadata:
 *
 * <ul>
 *   <li><b>Discovery</b> — set {@code issuer-uri} alone and the authorization, token, userinfo and
 *       JWKS endpoints are read from {@code {issuer}/.well-known/openid-configuration} at startup.
 *       This is the shorter configuration and the one that survives an OTDS upgrade moving a path.
 *   <li><b>Explicit endpoints</b> — set {@code authorization-uri} and {@code token-uri} (plus
 *       {@code jwk-set-uri} or {@code user-info-uri}) and no metadata is fetched. Needed for OTDS
 *       versions that do not serve a discovery document, and for the case where the issuer is only
 *       reachable from the browser under a different host name than from this service.
 * </ul>
 *
 * <p>Nothing here is read unless {@code enabled} is true, so a deployment that does not use OTDS
 * carries no OAuth beans, no filter chain and no startup cost.
 */
@Validated
@ConfigurationProperties(prefix = "app.sso")
public record SsoProperties(

        /** Master switch. Off, the SSO beans are never created and {@code /api/auth/sso} reports
         *  that the only way in is a password. */
        @DefaultValue("false") boolean enabled,

        /** What the sign-in button calls this provider. Shown to users, nothing else. */
        @DefaultValue("OpenText") String providerName,

        /** OTDS issuer, e.g. {@code https://otds.example.com/otdsws/oauth2}. On its own it means
         *  "discover everything from here"; alongside explicit endpoints it is only used to
         *  validate the {@code iss} claim of the ID token. */
        @DefaultValue("") String issuerUri,

        /** The OAuth client registered in the OTDS administration console for this application. */
        @DefaultValue("") String clientId,

        /** Secret of that client. Empty marks the client public, which is only correct if OTDS has
         *  it registered as public and PKCE-only. */
        @DefaultValue("") String clientSecret,

        /** {@code openid} is what makes this OIDC rather than bare OAuth: without it OTDS returns
         *  no ID token and there is no verified identity to provision a user from. */
        @DefaultValue({"openid", "profile", "email"}) List<String> scopes,

        /** Must match a redirect URL registered against the client in OTDS, character for
         *  character. The default expands to {@code https://your-host/api/login/oauth2/code/otds}
         *  — under {@code /api} so the bundled nginx proxies it to the backend with no extra rule.
         *  Pin it to a literal URL when this service sits behind a proxy it cannot see. */
        @DefaultValue("{baseUrl}/api/login/oauth2/code/{registrationId}") String redirectUri,

        /** How the client authenticates at the token endpoint: {@code client_secret_basic},
         *  {@code client_secret_post} or {@code none}. OTDS accepts Basic by default. */
        @DefaultValue("client_secret_basic") String clientAuthenticationMethod,

        /** Explicit endpoints. Empty means "discover from {@code issuer-uri}". */
        @DefaultValue("") String authorizationUri,
        @DefaultValue("") String tokenUri,
        @DefaultValue("") String userInfoUri,
        @DefaultValue("") String jwkSetUri,

        /** Claim naming the subject. {@code sub} is the one OTDS guarantees is stable across a
         *  rename, which is why the link between a local account and an OTDS identity is kept on
         *  it rather than on the e-mail address. */
        @DefaultValue("sub") String userNameAttribute,

        /** Claims searched, in order, for the address to key the local account on. OTDS releases
         *  differ on which of these carries the address, and an attribute mapping can move it
         *  again, so the lookup is a list rather than a single name. */
        @DefaultValue({"email", "mail", "upn", "preferred_username"}) List<String> emailClaims,

        /** Role given to an account provisioned on first OTDS sign-in. Left at {@code USER}:
         *  granting administration from a directory group is authorization, which this does not
         *  do — promote from the admin screen. */
        @DefaultValue("USER") UserRole defaultRole,

        /** Where the browser is sent once tokens are minted. Relative resolves against this
         *  service's own base URL, which is right when nginx serves the UI and the API on one
         *  origin; give an absolute URL when the UI is somewhere else (a Vite dev server, say). */
        @DefaultValue("/sso/callback") String loginSuccessUri,

        /** OTDS end-session endpoint. Set it and signing out also ends the OTDS session, so the
         *  next sign-in asks for credentials instead of silently returning the same user. */
        @DefaultValue("") String logoutUri,

        /** Lifetime of the one-time code handed to the UI after a successful OTDS sign-in. It is
         *  redeemed by the page it lands on, so seconds are plenty. */
        @DefaultValue("PT1M") Duration codeTtl
) {

    /** Registration id, and so the last path segment of both OAuth URLs below. */
    public static final String REGISTRATION_ID = "otds";

    /** Where the browser starts the handshake. Under {@code /api} to reuse the existing proxy rule. */
    public static final String AUTHORIZATION_BASE_URI = "/api/oauth2/authorization";

    /** Where OTDS sends the browser back with an authorization code. */
    public static final String REDIRECTION_BASE_URI = "/api/login/oauth2/code/*";

    /** The link the UI puts behind its "sign in with OTDS" button. */
    public String authorizationRequestUri() {
        return AUTHORIZATION_BASE_URI + "/" + REGISTRATION_ID;
    }

    /**
     * Whether the endpoints were spelled out rather than discovered.
     *
     * <p>Both halves of the handshake have to be present to skip discovery: an authorization URL
     * without a token URL describes where to send the user and not how to redeem what comes back.
     */
    public boolean hasExplicitEndpoints() {
        return StringUtils.hasText(authorizationUri) && StringUtils.hasText(tokenUri);
    }
}
