package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.auth.SsoService;
import com.kssasarma.confluencebot.config.SsoProperties;
import com.kssasarma.confluencebot.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * The seam between the OAuth handshake and this application's own sessions.
 *
 * <p>Past this point nothing is OAuth any more: the account is found or created, and the browser
 * leaves with the same kind of token pair a password sign-in produces, so every other endpoint,
 * filter and refresh path stays exactly as it was. Adding a directory did not add a second kind of
 * session to reason about — and would not add a third if a second directory were configured,
 * because which provider answered is read off the authentication rather than assumed.
 *
 * <p>The token pair itself is not what leaves — see {@link SsoRedirects}.
 */
class SsoLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoLoginSuccessHandler.class);

    private final SsoProperties properties;
    private final SsoUserProvisioner provisioner;
    private final SsoService ssoService;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    SsoLoginSuccessHandler(SsoProperties properties, SsoUserProvisioner provisioner, SsoService ssoService) {
        this.properties = properties;
        this.provisioner = provisioner;
        this.ssoService = ssoService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String target;
        try {
            // Read off the authentication rather than out of configuration: it is the registration
            // that actually answered, which stays correct if a deployment ever offers more than one.
            String providerId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
            User user = provisioner.provision(providerId, (OAuth2User) authentication.getPrincipal());
            target = SsoRedirects.successUrl(properties, request, ssoService.issueLoginCode(user), providerId);
        } catch (SsoProvisioningException e) {
            // Something the person can act on, or take to an administrator who can.
            log.warn("Rejected a single sign-on: {}", e.getMessage());
            target = SsoRedirects.errorUrl(properties, request, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not complete a single sign-on", e);
            target = SsoRedirects.errorUrl(properties, request,
                    "Sign-in could not be completed. Please try again.");
        }

        redirectStrategy.sendRedirect(request, response, target);
    }
}
