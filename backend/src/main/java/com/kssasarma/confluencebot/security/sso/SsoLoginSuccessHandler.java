package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.auth.SsoService;
import com.kssasarma.confluencebot.config.SsoProperties;
import com.kssasarma.confluencebot.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
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
 * session to reason about.
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
        String parameter = SsoRedirects.CODE_PARAM;
        String value;
        try {
            User user = provisioner.provision((OAuth2User) authentication.getPrincipal());
            value = ssoService.issueLoginCode(user);
        } catch (SsoProvisioningException e) {
            // Something the person can act on, or take to an administrator who can.
            log.warn("Rejected an OTDS sign-in: {}", e.getMessage());
            parameter = SsoRedirects.ERROR_PARAM;
            value = e.getMessage();
        } catch (RuntimeException e) {
            log.error("Could not complete an OTDS sign-in", e);
            parameter = SsoRedirects.ERROR_PARAM;
            value = "Sign-in could not be completed. Please try again.";
        }

        redirectStrategy.sendRedirect(request, response,
                SsoRedirects.targetUrl(properties, request, parameter, value));
    }
}
