package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoPropertiesFixture;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the {@code app.sso.*} block becomes the registration Spring Security drives the provider
 * from.
 *
 * <p>Everything asserted here fails at runtime as a redirect into someone else's error page with
 * no detail on this side of it, which is a bad place to learn that a client secret was blank or a
 * grant type wrong. The tests that matter most are therefore the ones about what a half-filled
 * configuration does: refusing to start, with a message naming the missing property, beats
 * starting and failing per-user later.
 *
 * <p>No vendor appears in these assertions on purpose. What is built here is an OAuth 2.0 client
 * against a server described by the standard, and a test that only passed for one product would
 * be the first sign the abstraction had leaked.
 */
class SsoClientRegistrationFactoryTest {

    @Test
    void explicitEndpointsAreUsedVerbatimAndNoMetadataIsFetched() {
        // No network here at all: an unreachable issuer would fail this test if discovery ran.
        ClientRegistration registration = SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .issuerUri("https://unreachable.invalid/oauth2")
                        .build());

        assertThat(registration.getRegistrationId()).isEqualTo("otds");
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://idp.example.com/oauth2/auth");
        assertThat(registration.getProviderDetails().getTokenUri())
                .isEqualTo("https://idp.example.com/oauth2/token");
        assertThat(registration.getProviderDetails().getJwkSetUri())
                .isEqualTo("https://idp.example.com/oauth2/jwks");
        assertThat(registration.getProviderDetails().getIssuerUri())
                .isEqualTo("https://unreachable.invalid/oauth2");
    }

    @Test
    void theFlowIsAlwaysAuthorizationCode() {
        ClientRegistration registration = SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().build());

        // The only grant that never lets this application see the user's directory password, and
        // the only one OAuth 2.1 still has for a browser sign-in.
        assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void aClientWithNoSecretAuthenticatesAsPublicWhateverTheConfiguredMethodSays() {
        // Left as client_secret_basic, which is the default and which a public client cannot do.
        ClientRegistration registration = SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().clientSecret("").build());

        assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
    }

    @Test
    void theConfiguredClientAuthenticationMethodIsHonouredWhenThereIsASecret() {
        ClientRegistration registration = SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .clientAuthenticationMethod("client_secret_post")
                        .build());

        assertThat(registration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
    }

    @Test
    void anUnknownClientAuthenticationMethodIsRejectedByName() {
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .clientAuthenticationMethod("magic")
                        .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void aMissingClientIdIsRefusedBeforeAnythingElseIsRead() {
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().clientId("").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.client-id");
    }

    @Test
    void neitherAnIssuerNorEndpointsIsRefusedWithBothWaysOutNamed() {
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.aProvider().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("app.sso.issuer-uri", "app.sso.authorization-uri");
    }

    @Test
    void anAuthorizationUriWithNoTokenUriIsNotEnoughToSkipDiscovery() {
        // Half a handshake. Falling through to discovery with an empty issuer is the failure this
        // guards: the message would then be about a missing issuer, not the missing token URL.
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.aProvider()
                        .authorizationUri("https://idp.example.com/oauth2/auth")
                        .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.token-uri");
    }

    @Test
    void explicitEndpointsWithNothingToVerifyTheIdentityAgainstAreRefused() {
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().jwkSetUri("").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("app.sso.jwk-set-uri", "app.sso.user-info-uri");
    }

    @Test
    void discoveryAgainstAnUnreachableIssuerFailsWithTheWayOutOfIt() {
        // Loopback on a port nothing listens on: refused immediately, with no DNS lookup and no
        // proxy in the way, so this asserts the error handling rather than a network timeout.
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.aProvider()
                        .issuerUri("http://127.0.0.1:1/oauth2")
                        .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.authorization-uri");
    }

    @Test
    void theProviderNameIsCarriedThroughAsTheClientName() {
        ClientRegistration registration = SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .providerName("OpenText Directory Services")
                        .scopes(List.of("openid", "email"))
                        .build());

        assertThat(registration.getClientName()).isEqualTo("OpenText Directory Services");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "email");
    }

    @Test
    void theRegistrationIdIsWhicheverTheDeploymentChose() {
        // It is the last segment of the redirect URL registered with the provider, so a deployment
        // has to be able to pick it — and nothing in this codebase may assume any one value.
        assertThat(SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().providerId("entra").build())
                .getRegistrationId()).isEqualTo("entra");

        assertThat(SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().providerId("keycloak").build())
                .getRegistrationId()).isEqualTo("keycloak");
    }

    @Test
    void aBlankProviderIdIsRefusedBecauseItWouldEmptyTheCallbackUrl() {
        assertThatThrownBy(() -> SsoClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().providerId("").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.provider-id");
    }
}
