package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.auth.SsoService;
import com.kssasarma.confluencebot.config.SsoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

/**
 * That switching single sign-on off leaves nothing behind.
 *
 * <p>This is the promise made to every deployment that does not use OTDS: adding the feature added
 * an OAuth client library to the classpath, and if any of it were wired up unconditionally then a
 * blank {@code OTDS_ISSUER_URI} would become a startup failure for people who never asked for the
 * feature. The condition is what stops that, and a condition nothing asserts is a condition that
 * quietly stops applying.
 */
class SsoSecurityConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Registered as itself, not through a subclass: @Conditional is not @Inherited, so a
        // subclass would quietly drop the very condition under test and pass for the wrong reason.
        .withUserConfiguration(SsoSecurityConfig.class, PropertiesUnderTest.class)
            .withBean(SsoUserProvisioner.class, () -> mock(SsoUserProvisioner.class))
            .withBean(SsoService.class, () -> mock(SsoService.class))
            .withBean(HttpSecurity.class, () -> mock(HttpSecurity.class, RETURNS_SELF));

    @Test
    void switchedOffNothingIsRegisteredAndNoConfigurationIsRead() {
        // Not one property beyond the switch — the state a deployment upgrading into this feature
        // is in on the day it upgrades.
        runner.withPropertyValues("app.sso.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
            assertThat(context).doesNotHaveBean(SsoLoginSuccessHandler.class);
            assertThat(context).doesNotHaveBean(SsoLoginFailureHandler.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test
    void absentEntirelyIsTreatedTheSameAsSwitchedOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
        });
    }

    @Test
    void switchedOnTheProviderIsRegisteredFromTheConfiguredEndpoints() {
        runner.withPropertyValues(
                "app.sso.enabled=true",
                "app.sso.client-id=confluence-bot",
                "app.sso.client-secret=s3cret",
                "app.sso.authorization-uri=https://otds.example.com/otdsws/oauth2/auth",
                "app.sso.token-uri=https://otds.example.com/otdsws/oauth2/token",
                "app.sso.jwk-set-uri=https://otds.example.com/otdsws/oauth2/jwks"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
            assertThat(context).hasSingleBean(SsoLoginSuccessHandler.class);
            assertThat(context).hasSingleBean(SsoLoginFailureHandler.class);

            ClientRegistrationRepository registrations = context.getBean(ClientRegistrationRepository.class);
            assertThat(registrations.findByRegistrationId(SsoProperties.REGISTRATION_ID)).isNotNull();
        });
    }

    @Test
    void switchedOnWithNothingConfiguredFailsTheDeploymentRatherThanTheFirstPersonToSignIn() {
        runner.withPropertyValues("app.sso.enabled=true").run(context ->
                assertThat(context).getFailure().hasMessageContaining("app.sso.client-id"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SsoProperties.class)
    static class PropertiesUnderTest {
    }
}
