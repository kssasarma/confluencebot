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
 * How the {@code app.sso.*} block becomes the registration Spring Security drives OTDS from.
 *
 * <p>Everything asserted here fails at runtime as a redirect into an OpenText error page with no
 * detail on this side of it, which is a bad place to learn that a client secret was blank or a
 * grant type wrong. The tests that matter most are therefore the ones about what a half-filled
 * configuration does: refusing to start, with a message naming the missing property, beats
 * starting and failing per-user later.
 */
class OtdsClientRegistrationFactoryTest {

    @Test
    void explicitEndpointsAreUsedVerbatimAndNoMetadataIsFetched() {
        // No network here at all: an unreachable issuer would fail this test if discovery ran.
        ClientRegistration registration = OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .issuerUri("https://unreachable.invalid/otdsws/oauth2")
                        .build());

        assertThat(registration.getRegistrationId()).isEqualTo("otds");
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://otds.example.com/otdsws/oauth2/auth");
        assertThat(registration.getProviderDetails().getTokenUri())
                .isEqualTo("https://otds.example.com/otdsws/oauth2/token");
        assertThat(registration.getProviderDetails().getJwkSetUri())
                .isEqualTo("https://otds.example.com/otdsws/oauth2/jwks");
        assertThat(registration.getProviderDetails().getIssuerUri())
                .isEqualTo("https://unreachable.invalid/otdsws/oauth2");
    }

    @Test
    void theFlowIsAlwaysAuthorizationCode() {
        ClientRegistration registration = OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().build());

        // The only grant that never lets this application see the user's directory password, and
        // the only one OAuth 2.1 still has for a browser sign-in.
        assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void aClientWithNoSecretAuthenticatesAsPublicWhateverTheConfiguredMethodSays() {
        // Left as client_secret_basic, which is the default and which a public client cannot do.
        ClientRegistration registration = OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().clientSecret("").build());

        assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
    }

    @Test
    void theConfiguredClientAuthenticationMethodIsHonouredWhenThereIsASecret() {
        ClientRegistration registration = OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .clientAuthenticationMethod("client_secret_post")
                        .build());

        assertThat(registration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
    }

    @Test
    void anUnknownClientAuthenticationMethodIsRejectedByName() {
        assertThatThrownBy(() -> OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .clientAuthenticationMethod("magic")
                        .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void aMissingClientIdIsRefusedBeforeAnythingElseIsRead() {
        assertThatThrownBy(() -> OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().clientId("").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.client-id");
    }

    @Test
    void neitherAnIssuerNorEndpointsIsRefusedWithBothWaysOutNamed() {
        assertThatThrownBy(() -> OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.aProvider().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("app.sso.issuer-uri", "app.sso.authorization-uri");
    }

    @Test
    void anAuthorizationUriWithNoTokenUriIsNotEnoughToSkipDiscovery() {
        // Half a handshake. Falling through to discovery with an empty issuer is the failure this
        // guards: the message would then be about a missing issuer, not the missing token URL.
        assertThatThrownBy(() -> OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.aProvider()
                        .authorizationUri("https://otds.example.com/otdsws/oauth2/auth")
                        .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.token-uri");
    }

    @Test
    void explicitEndpointsWithNothingToVerifyTheIdentityAgainstAreRefused() {
        assertThatThrownBy(() -> OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints().jwkSetUri("").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("app.sso.jwk-set-uri", "app.sso.user-info-uri");
    }

    @Test
    void discoveryAgainstAnUnreachableIssuerFailsWithTheWayOutOfIt() {
        // Loopback on a port nothing listens on: refused immediately, with no DNS lookup and no
        // proxy in the way, so this asserts the error handling rather than a network timeout.
        assertThatThrownBy(() -> OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.aProvider()
                        .issuerUri("http://127.0.0.1:1/otdsws/oauth2")
                        .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sso.authorization-uri");
    }

    @Test
    void theProviderNameIsCarriedThroughAsTheClientName() {
        ClientRegistration registration = OtdsClientRegistrationFactory.create(
                SsoPropertiesFixture.withExplicitEndpoints()
                        .providerName("OpenText Directory Services")
                        .scopes(List.of("openid", "email"))
                        .build());

        assertThat(registration.getClientName()).isEqualTo("OpenText Directory Services");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "email");
    }
}
