package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.user.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Single sign-on against an OAuth 2.0 / OpenID Connect provider.
 *
 * <p>Nothing here names a vendor. OpenText Directory Services is the provider this was built for,
 * but OTDS is an ordinary OIDC authorization server and so is every alternative a company might
 * put in front of it — Entra ID, Okta, Keycloak, Ping, Auth0 — so what is configured is the
 * protocol, not the product. Switching provider is a change to these values and nothing else.
 *
 * <p>Password sign-in is unaffected by any of it. The two are alternatives offered side by side,
 * not a migration: a deployment with no directory keeps the login form it has, one with a
 * directory gains a button next to it, and the account behind either is the same row in
 * {@code users}.
 *
 * <p>A provider can be described two ways, because deployments differ in whether their server
 * publishes metadata:
 *
 * <ul>
 *   <li><b>Discovery</b> — set {@code issuer-uri} alone and the authorization, token, userinfo and
 *       JWKS endpoints are read from {@code {issuer}/.well-known/openid-configuration} at startup.
 *       This is the shorter configuration and the one that survives the provider moving a path.
 *   <li><b>Explicit endpoints</b> — set {@code authorization-uri} and {@code token-uri} (plus
 *       {@code jwk-set-uri} or {@code user-info-uri}) and no metadata is fetched. Needed for a
 *       server that publishes no discovery document, and for the case where the issuer is only
 *       reachable from the browser under a different host name than from this service.
 * </ul>
 *
 * <p>Nothing here is read unless {@code enabled} is true, so a deployment that signs in with
 * passwords alone carries no OAuth beans, no filter chain and no startup cost.
 */
@Validated
@ConfigurationProperties(prefix = "app.sso")
public record SsoProperties(

        /** Master switch. Off, the SSO beans are never created and {@code /api/auth/sso} reports
         *  that the only way in is a password. */
        @DefaultValue("false") boolean enabled,

        /**
         * Skips the button. The sign-in screen leaves for the provider on its own, without
         * anyone clicking anything — the deployment's choice, not each visitor's.
         *
         * <p>The password form is never actually removed: a directory outage must not also strand
         * the bootstrap administrator, who exists in no directory. What this decides is only
         * whether the screen leaves immediately, and the frontend keeps a deliberately quiet
         * "sign in with a password instead" escape hatch even here. Ignored while {@code enabled}
         * is false — there is nothing to redirect to.
         */
        @DefaultValue("false") boolean enforced,

        /**
         * Short identifier for the provider, used in URLs rather than shown to anyone.
         *
         * <p>It is the last segment of both OAuth URLs, so the redirect URL registered with the
         * provider ends {@code /api/login/oauth2/code/<this>}. Changing it after registering that
         * URL breaks the callback, which is the one reason to think before setting it.
         *
         * <p>Defaulted to {@code otds} because that is the directory this is deployed against, so
         * the common case configures nothing. It is a default and not an assumption: point it at
         * {@code entra} or {@code keycloak} and everything downstream follows, because nothing
         * reads this value expecting a particular one.
         */
        @DefaultValue("otds") String providerId,

        /** What the sign-in button calls this provider: "Continue with <this>". Shown to users. */
        @DefaultValue("OpenText") String providerName,

        /** Issuer, e.g. {@code https://otds.example.com/otdsws/oauth2} or
         *  {@code https://login.microsoftonline.com/<tenant>/v2.0}. On its own it means "discover
         *  everything from here"; alongside explicit endpoints it is only used to validate the
         *  {@code iss} claim of the ID token. */
        @DefaultValue("") String issuerUri,

        /** The OAuth client registered with the provider for this application. */
        @DefaultValue("") String clientId,

        /** Secret of that client. Empty marks the client public, which is only correct if the
         *  provider has it registered as public and PKCE-only. */
        @DefaultValue("") String clientSecret,

        /** {@code openid} is what makes this OIDC rather than bare OAuth: without it the provider
         *  returns no ID token and there is no verified identity to provision a user from. */
        @DefaultValue({"openid", "profile", "email"}) List<String> scopes,

        /** Must match a redirect URL registered against the client, character for character. The
         *  default expands to {@code https://your-host/api/login/oauth2/code/<provider-id>} —
         *  under {@code /api} so the bundled nginx proxies it with no extra rule. Pin it to a
         *  literal URL when this service sits behind a proxy it cannot see. */
        @DefaultValue("{baseUrl}/api/login/oauth2/code/{registrationId}") String redirectUri,

        /** How the client authenticates at the token endpoint: {@code client_secret_basic},
         *  {@code client_secret_post} or {@code none}. Basic is the common default. */
        @DefaultValue("client_secret_basic") String clientAuthenticationMethod,

        /** Explicit endpoints. Empty means "discover from {@code issuer-uri}". */
        @DefaultValue("") String authorizationUri,
        @DefaultValue("") String tokenUri,
        @DefaultValue("") String userInfoUri,
        @DefaultValue("") String jwkSetUri,

        /** Claim naming the subject. {@code sub} is the one every OIDC provider guarantees is
         *  stable across a rename, which is why the link between a local account and a directory
         *  identity is kept on it rather than on the e-mail address. */
        @DefaultValue("sub") String userNameAttribute,

        /** Claims searched, in order, for the address to key the local account on. Providers
         *  differ on which of these carries it, and an attribute mapping can move it again, so
         *  the lookup is a list rather than a single name. */
        @DefaultValue({"email", "mail", "upn", "preferred_username"}) List<String> emailClaims,

        /** Claim carrying the person's display name. Read on every sign-in and written over
         *  whatever is stored locally — the directory is the source of truth for this field, not
         *  a value the user set once here. Falls back to {@code given_name}/{@code family_name}
         *  combined when this claim is absent. */
        @DefaultValue("name") String nameAttribute,

        /** Roles given to an account provisioned on first sign-in. Plural because an account
         *  here holds a set of roles, not one; left at {@code USER} because granting
         *  administration from a directory group is authorization, which this does not do —
         *  promote from the admin screen. */
        @DefaultValue("USER") Set<UserRole> defaultRoles,

        /** Where the browser is sent once tokens are minted. Relative resolves against this
         *  service's own base URL, which is only ever right if something makes the UI and the API
         *  share an origin — nothing in this deployment does that any more. Set this to the UI's
         *  absolute URL (its {@code VITE_BASE_PATH} included), e.g.
         *  {@code https://ui.example.com/sso/callback}. */
        @DefaultValue("/sso/callback") String loginSuccessUri,

        /** The provider's end-session endpoint. Set it and signing out ends the provider's session
         *  too, so the next sign-in asks for credentials instead of silently returning the same
         *  user. */
        @DefaultValue("") String logoutUri,

        /** Lifetime of the one-time code handed to the UI after a successful sign-in. It is
         *  redeemed by the page it lands on, so seconds are plenty. */
        @DefaultValue("PT1M") Duration codeTtl,

        /**
         * This service's own externally-reachable origin, pinned literally. Empty means "work it
         * out from the request" — which only comes out right if every proxy between the browser
         * and this service adds correct {@code X-Forwarded-Host}/{@code X-Forwarded-Proto}
         * headers, the same way {@link #redirectUri} only resolves correctly off {@code {baseUrl}}
         * under that condition. A proxy that forwards the request with its own upstream target as
         * the {@code Host} header (rather than the original public one) breaks that derivation
         * silently — the authorization URL comes out pointed at an address only reachable from
         * inside the proxy's own network. Set this once and the derivation is never attempted:
         * {@code https://api.bot.example.com} or, behind a path-prefixing proxy,
         * {@code https://api.bot.example.com/confluencebot-backend}.
         */
        @DefaultValue("") String publicBaseUrl
) {

    /** Where the browser starts the handshake, relative to this service's own origin. The UI turns
     *  this into an absolute URL itself — see {@link com.kssasarma.confluencebot.auth.SsoServiceImpl}
     *  — since nothing proxies the UI and this service onto one origin. */
    public static final String AUTHORIZATION_BASE_URI = "/api/oauth2/authorization";

    /** Where the provider sends the browser back with an authorization code. The trailing
     *  wildcard is the provider id, which Spring Security reads back out of the URL. */
    public static final String REDIRECTION_BASE_URI = "/api/login/oauth2/code/*";

    /** The link the UI puts behind its "Continue with …" button. */
    public String authorizationRequestUri() {
        return AUTHORIZATION_BASE_URI + "/" + providerId;
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
