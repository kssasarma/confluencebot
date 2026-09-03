package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * Sends a failed handshake back to the sign-in screen instead of Spring Security's default
 * {@code /login?error}, which is a page this application does not have.
 *
 * <p>The error <em>code</em> travels — {@code invalid_grant}, {@code invalid_client},
 * {@code access_denied} — because it is the single most useful thing to be able to read off a
 * screen and quote to whoever administers OTDS. The provider's free-text description does not: it
 * is written for a developer, arrives unsanitised from another system, and has a habit of
 * containing internal host names.
 */
class SsoLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoLoginFailureHandler.class);

    private final SsoProperties properties;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    SsoLoginFailureHandler(SsoProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OTDS sign-in failed: {}", exception.getMessage(), exception);

        redirectStrategy.sendRedirect(request, response,
                SsoRedirects.targetUrl(properties, request, SsoRedirects.ERROR_PARAM, message(exception)));
    }

    private String message(AuthenticationException exception) {
        String prefix = "Sign-in with " + properties.providerName() + " failed";
        if (exception instanceof OAuth2AuthenticationException oauth2
                && StringUtils.hasText(oauth2.getError().getErrorCode())) {
            return prefix + " (" + oauth2.getError().getErrorCode() + ").";
        }
        return prefix + ". Please try again.";
    }
}
