package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoPropertiesFixture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the browser is sent when it comes back from OTDS, and — more importantly — what part of
 * the URL the one-time code is written into.
 *
 * <p>The fragment is the whole point. A query parameter would be recorded by every access log and
 * reverse proxy on the way in and sent onwards in the {@code Referer} of the first request the
 * landing page makes; a fragment is never transmitted at all. Moving that one character is the
 * difference between a credential the browser holds and a credential in a log file.
 */
class SsoRedirectsTest {

    @Test
    void aRelativeTargetIsResolvedAgainstThisService() {
        String url = SsoRedirects.targetUrl(
                SsoPropertiesFixture.aProvider().build(), request("https", "bot.example.com", 443),
                SsoRedirects.CODE_PARAM, "abc123");

        assertThat(url).isEqualTo("https://bot.example.com/sso/callback#sso_code=abc123");
    }

    @Test
    void anAbsoluteTargetIsUsedAsGivenSoTheUiCanLiveSomewhereElse() {
        String url = SsoRedirects.targetUrl(
                SsoPropertiesFixture.aProvider().loginSuccessUri("http://localhost:5173/sso/callback").build(),
                request("http", "localhost", 8080), SsoRedirects.CODE_PARAM, "abc123");

        assertThat(url).isEqualTo("http://localhost:5173/sso/callback#sso_code=abc123");
    }

    @Test
    void aTargetWithoutALeadingSlashStillResolves() {
        String url = SsoRedirects.targetUrl(
                SsoPropertiesFixture.aProvider().loginSuccessUri("sso/callback").build(),
                request("https", "bot.example.com", 443), SsoRedirects.CODE_PARAM, "abc123");

        assertThat(url).isEqualTo("https://bot.example.com/sso/callback#sso_code=abc123");
    }

    @Test
    void aConfiguredFragmentIsDroppedRatherThanProducingTwo() {
        String url = SsoRedirects.targetUrl(
                SsoPropertiesFixture.aProvider().loginSuccessUri("/app#/landing").build(),
                request("https", "bot.example.com", 443), SsoRedirects.CODE_PARAM, "abc123");

        assertThat(url).isEqualTo("https://bot.example.com/app#sso_code=abc123");
    }

    @Test
    void aNonDefaultPortIsKeptOrTheRedirectLandsNowhere() {
        String url = SsoRedirects.targetUrl(
                SsoPropertiesFixture.aProvider().build(), request("http", "localhost", 8080),
                SsoRedirects.CODE_PARAM, "abc123");

        assertThat(url).isEqualTo("http://localhost:8080/sso/callback#sso_code=abc123");
    }

    @Test
    void anErrorMessageIsEncodedSoItsSpacesAndPunctuationSurviveTheUrl() {
        String url = SsoRedirects.targetUrl(
                SsoPropertiesFixture.aProvider().build(), request("https", "bot.example.com", 443),
                SsoRedirects.ERROR_PARAM, "This account has been disabled. Contact an administrator.");

        assertThat(url).startsWith("https://bot.example.com/sso/callback#sso_error=")
                .doesNotContain(" ")
                // A raw '#' or '&' in the message would truncate everything after it.
                .endsWith("This+account+has+been+disabled.+Contact+an+administrator.");
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }
}
