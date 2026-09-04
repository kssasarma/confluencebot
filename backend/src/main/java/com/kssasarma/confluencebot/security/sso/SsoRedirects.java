package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the URL the browser lands on when it comes back from the identity provider.
 *
 * <p>The result is always carried in the fragment. A fragment is never sent to a server — not to
 * this one, not in the {@code Referer} of whatever the page loads next — so the single-use code
 * reaches the application's own JavaScript and stops there. A query string would reach the web
 * server's access log on the way in, and the log is the one place a credential outlives its minute.
 */
final class SsoRedirects {

    /** Fragment parameter carrying the one-time code on success. */
    static final String CODE_PARAM = "sso_code";

    /** Fragment parameter carrying a message to show on the sign-in screen on failure. */
    static final String ERROR_PARAM = "sso_error";

    /** Fragment parameter naming the provider that answered. */
    static final String PROVIDER_PARAM = "sso_provider";

    private SsoRedirects() {
    }

    /**
     * The provider is sent alongside the code because signing out has to know where this session
     * came from. A deployment can be re-pointed at a different directory, and a stale marker
     * saying only "this was an SSO session" would send the next sign-out to the wrong provider.
     */
    static String successUrl(SsoProperties properties, HttpServletRequest request,
                             String code, String providerId) {
        Map<String, String> fragment = new LinkedHashMap<>();
        fragment.put(CODE_PARAM, code);
        fragment.put(PROVIDER_PARAM, providerId);
        return build(properties, request, fragment);
    }

    static String errorUrl(SsoProperties properties, HttpServletRequest request, String message) {
        return build(properties, request, Map.of(ERROR_PARAM, message));
    }

    private static String build(SsoProperties properties, HttpServletRequest request,
                                Map<String, String> fragment) {
        StringBuilder url = new StringBuilder(resolveBase(properties, request)).append('#');
        String separator = "";
        for (Map.Entry<String, String> entry : fragment.entrySet()) {
            url.append(separator).append(entry.getKey()).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            separator = "&";
        }
        return url.toString();
    }

    /**
     * An absolute {@code login-success-uri} is used as given — that is the case where the UI is
     * served from somewhere this service is not. A relative one is resolved against this request,
     * which is right when nginx puts the UI and the API on one origin, and which reads the
     * forwarded host and scheme correctly as long as {@code server.forward-headers-strategy} is on.
     */
    private static String resolveBase(SsoProperties properties, HttpServletRequest request) {
        String configured = properties.loginSuccessUri();

        // A fragment of its own would collide with the one being appended.
        int fragment = configured.indexOf('#');
        if (fragment >= 0) {
            configured = configured.substring(0, fragment);
        }

        if (configured.startsWith("http://") || configured.startsWith("https://")) {
            return configured;
        }
        String origin = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        return origin + (configured.startsWith("/") ? configured : "/" + configured);
    }
}
