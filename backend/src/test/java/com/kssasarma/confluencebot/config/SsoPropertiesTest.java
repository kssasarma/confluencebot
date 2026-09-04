package com.kssasarma.confluencebot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The values a deployment gets without asking for them.
 *
 * <p>{@code provider-id} is the one that has to be pinned. It is the last path segment of the
 * redirect URL registered with the identity provider, so changing its default silently breaks the
 * callback for every deployment that took it — and the failure surfaces on the provider's error
 * page, not in any log here. Cheap to fix while unreleased; a breaking change afterwards.
 */
class SsoPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesUnderTest.class);

    @Test
    void theShippedDefaultsDescribeTheDirectoryThisIsDeployedAgainst() {
        // Configuring nothing but a client should be enough for the common case.
        runner.run(context -> {
            SsoProperties properties = context.getBean(SsoProperties.class);

            assertThat(properties.providerId()).isEqualTo("otds");
            assertThat(properties.providerName()).isEqualTo("OpenText");
            assertThat(properties.authorizationRequestUri()).isEqualTo("/api/oauth2/authorization/otds");
        });
    }

    @Test
    void itIsADefaultRatherThanAnAssumption() {
        runner.withPropertyValues("app.sso.provider-id=entra", "app.sso.provider-name=Microsoft")
                .run(context -> {
                    SsoProperties properties = context.getBean(SsoProperties.class);

                    assertThat(properties.providerId()).isEqualTo("entra");
                    assertThat(properties.authorizationRequestUri())
                            .isEqualTo("/api/oauth2/authorization/entra");
                });
    }

    @Test
    void singleSignOnIsOffUntilAskedFor() {
        // The promise to every deployment that signs in with passwords alone.
        runner.run(context -> assertThat(context.getBean(SsoProperties.class).enabled()).isFalse());
    }

    @Test
    void discoveryIsTheDefaultRouteAndExplicitEndpointsNeedBothHalves() {
        runner.run(context -> assertThat(context.getBean(SsoProperties.class).hasExplicitEndpoints()).isFalse());

        runner.withPropertyValues("app.sso.authorization-uri=https://idp.example.com/oauth2/auth")
                .run(context -> assertThat(context.getBean(SsoProperties.class).hasExplicitEndpoints())
                        .describedAs("an authorization URL with no token URL is half a handshake")
                        .isFalse());

        runner.withPropertyValues(
                "app.sso.authorization-uri=https://idp.example.com/oauth2/auth",
                "app.sso.token-uri=https://idp.example.com/oauth2/token")
                .run(context -> assertThat(context.getBean(SsoProperties.class).hasExplicitEndpoints()).isTrue());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SsoProperties.class)
    static class PropertiesUnderTest {
    }
}
