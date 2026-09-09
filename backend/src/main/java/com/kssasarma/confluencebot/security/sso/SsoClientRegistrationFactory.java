package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * Turns the {@code app.sso.*} block into the {@link ClientRegistration} Spring Security drives the
 * handshake from.
 *
 * <p>Provider-agnostic on purpose. What arrives here is an OAuth 2.0 authorization server
 * described by the standard — an issuer, a client, a pair of endpoints — and the same code builds
 * a registration for OpenText Directory Services, Entra ID, Okta or Keycloak without knowing
 * which it is. There is no place in this class for a vendor's name, and a change that needs one
 * is a sign the abstraction slipped.
 *
 * <p>Two routes in, because deployments differ in whether their server publishes metadata.
 * Discovery is the one to prefer — it reads the endpoints, the JWKS location and the signing
 * algorithms straight from the provider, so a version that moves a path costs no configuration
 * change — but it fails closed at startup if the provider is unreachable, and there are servers
 * and reverse proxy arrangements where the document is not served at all. Spelling out the two
 * endpoints is the way through that, and skips the metadata fetch entirely.
 */
final class SsoClientRegistrationFactory {

    private static final Logger log = LoggerFactory.getLogger(SsoClientRegistrationFactory.class);

    private SsoClientRegistrationFactory() {
    }

    static ClientRegistration create(SsoProperties properties) {
        require(StringUtils.hasText(properties.providerId()),
                "app.sso.provider-id must be set when single sign-on is enabled");
        require(StringUtils.hasText(properties.clientId()),
                "app.sso.client-id must be set when single sign-on is enabled");

        ClientRegistration.Builder builder = properties.hasExplicitEndpoints()
                ? fromExplicitEndpoints(properties)
                : fromDiscovery(properties);

        return builder
                .registrationId(properties.providerId())
                .clientName(properties.providerName())
                .clientId(properties.clientId())
                .clientSecret(StringUtils.hasText(properties.clientSecret()) ? properties.clientSecret() : null)
                .clientAuthenticationMethod(clientAuthenticationMethod(properties))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.redirectUri())
                .scope(properties.scopes())
                .userNameAttributeName(properties.userNameAttribute())
                .build();
    }

    private static ClientRegistration.Builder fromDiscovery(SsoProperties properties) {
        require(StringUtils.hasText(properties.issuerUri()),
                "Single sign-on needs either app.sso.issuer-uri, or app.sso.authorization-uri and "
                        + "app.sso.token-uri spelled out");

        log.info("Discovering OpenID provider metadata from {}", properties.issuerUri());
        try {
            // Tries OIDC discovery first and RFC 8414 authorization-server metadata second, which
            // between them cover every layout a provider publishes the document under.
            return ClientRegistrations.fromIssuerLocation(properties.issuerUri());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not read OpenID provider metadata from " + properties.issuerUri()
                            + ". Check the issuer is reachable from this service, or set "
                            + "app.sso.authorization-uri and app.sso.token-uri to skip discovery.", e);
        }
    }

    private static ClientRegistration.Builder fromExplicitEndpoints(SsoProperties properties) {
        require(StringUtils.hasText(properties.jwkSetUri()) || StringUtils.hasText(properties.userInfoUri()),
                "With explicit endpoints, set app.sso.jwk-set-uri so the ID token signature can be "
                        + "verified, or app.sso.user-info-uri to read the identity from the "
                        + "provider instead");

        log.info("Using explicitly configured endpoints for provider '{}'; provider metadata will "
                + "not be fetched", properties.providerId());
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(properties.providerId())
                .authorizationUri(properties.authorizationUri())
                .tokenUri(properties.tokenUri());

        if (StringUtils.hasText(properties.userInfoUri())) {
            builder.userInfoUri(properties.userInfoUri());
        }
        if (StringUtils.hasText(properties.jwkSetUri())) {
            builder.jwkSetUri(properties.jwkSetUri());
        }
        // Only used to check the ID token's `iss`, and only when it was given.
        if (StringUtils.hasText(properties.issuerUri())) {
            builder.issuerUri(properties.issuerUri());
        }
        return builder;
    }

    private static ClientAuthenticationMethod clientAuthenticationMethod(SsoProperties properties) {
        String configured = properties.clientAuthenticationMethod();

        // A client with no secret cannot authenticate at the token endpoint however it is
        // configured to try, so the sane method is derivable and worth deriving: getting this
        // wrong surfaces as an opaque 401 from the provider halfway through a redirect.
        if (!StringUtils.hasText(properties.clientSecret())) {
            return ClientAuthenticationMethod.NONE;
        }
        return switch (configured.trim().toLowerCase()) {
            case "client_secret_post" -> ClientAuthenticationMethod.CLIENT_SECRET_POST;
            case "client_secret_jwt" -> ClientAuthenticationMethod.CLIENT_SECRET_JWT;
            case "private_key_jwt" -> ClientAuthenticationMethod.PRIVATE_KEY_JWT;
            case "none" -> ClientAuthenticationMethod.NONE;
            case "client_secret_basic", "" -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
            default -> throw new IllegalStateException(
                    "Unsupported app.sso.client-authentication-method: " + configured);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
