package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.auth.SsoService;
import com.kssasarma.confluencebot.config.SsoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything single sign-on adds to the filter stack, and nothing when it is switched off.
 *
 * <p>The handshake gets a filter chain of its own, ahead of the application's. The two want
 * opposite things from a session: the API is stateless and must stay that way, while the
 * authorization-code flow has to remember the {@code state} and PKCE verifier it generated across
 * a round trip through the browser to the provider and back. Scoping a session-bearing chain to the two
 * OAuth URLs buys that memory for the twenty seconds it is needed without any of it leaking into
 * how the rest of the application is authenticated.
 *
 * <p>Both URLs sit under {@code /api} so the reverse proxy in front of this service — which
 * already forwards {@code /api} and nothing else — needs no new rule to make SSO reachable.
 *
 * <p>One consequence worth knowing before scaling out: that session lives in this instance's
 * memory, so a load balancer must keep one sign-in on one instance. Everything after sign-in is
 * bearer-token authenticated and does not care.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.sso", name = "enabled", havingValue = "true")
public class SsoSecurityConfig {

    /**
     * Resolved once at startup. When discovery is in use this is the call that reads the
     * provider's metadata, so a mistyped issuer or an unreachable directory fails the deployment
     * rather than the first person who tries to sign in.
     *
     * <p>A repository holds a collection, and this one holds a single registration only because a
     * single provider is configured. Offering a second is a matter of building a second
     * registration and passing it here; nothing downstream of this bean knows how many there are.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(SsoProperties properties) {
        return new InMemoryClientRegistrationRepository(SsoClientRegistrationFactory.create(properties));
    }

    @Bean
    public SsoLoginSuccessHandler ssoLoginSuccessHandler(SsoProperties properties,
                                                         SsoUserProvisioner provisioner,
                                                         SsoService ssoService) {
        return new SsoLoginSuccessHandler(properties, provisioner, ssoService);
    }

    @Bean
    public SsoLoginFailureHandler ssoLoginFailureHandler(SsoProperties properties) {
        return new SsoLoginFailureHandler(properties);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain ssoFilterChain(HttpSecurity http,
                                              ClientRegistrationRepository clientRegistrationRepository,
                                              SsoLoginSuccessHandler successHandler,
                                              SsoLoginFailureHandler failureHandler) throws Exception {
        return http
                // Wildcards rather than the configured provider id: the id is a deployment's to
                // choose, and a chain that matched only one would stop routing the moment it changed.
                .securityMatcher(SsoProperties.AUTHORIZATION_BASE_URI + "/*",
                                 SsoProperties.REDIRECTION_BASE_URI)
                // Both URLs are top-level browser navigations, not requests the application makes:
                // a token has nowhere to be attached from, and the callback's own defence against
                // being replayed by another site is the `state` parameter, which is checked below.
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .authorizationEndpoint(a -> a.baseUri(SsoProperties.AUTHORIZATION_BASE_URI))
                        .redirectionEndpoint(r -> r.baseUri(SsoProperties.REDIRECTION_BASE_URI))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .build();
    }
}
