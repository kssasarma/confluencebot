package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoPropertiesFixture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the browser is sent when it comes back from the provider, and — more importantly — what
 * part of the URL the one-time code is written into.
 *
 * <p>The fragment is the whole point. A query parameter would be recorded by every access log and
 * reverse proxy on the way in and sent onwards in the {@code Referer} of the first request the
 * landing page makes; a fragment is never transmitted at all. Moving that one character is the
 * difference between a credential the browser holds and a credential in a log file.
 */
class SsoRedirectsTest {

    @Test
    void aRelativeTargetIsResolvedAgainstThisService() {
        String url = SsoRedirects.successUrl(
                SsoPropertiesFixture.aProvider().build(), request("https", "bot.example.com", 443),
                "abc123", "otds");

        assertThat(url).isEqualTo("https://bot.example.com/sso/callback#sso_code=abc123&sso_provider=otds");
    }

    @Test
    void anAbsoluteTargetIsUsedAsGivenSoTheUiCanLiveSomewhereElse() {
        String url = SsoRedirects.successUrl(
                SsoPropertiesFixture.aProvider().loginSuccessUri("http://localhost:5173/sso/callback").build(),
                request("http", "localhost", 8080), "abc123", "otds");

        assertThat(url).startsWith("http://localhost:5173/sso/callback#sso_code=abc123");
    }

    @Test
    void aTargetWithoutALeadingSlashStillResolves() {
        String url = SsoRedirects.successUrl(
                SsoPropertiesFixture.aProvider().loginSuccessUri("sso/callback").build(),
                request("https", "bot.example.com", 443), "abc123", "otds");

        assertThat(url).startsWith("https://bot.example.com/sso/callback#sso_code=abc123");
    }

    @Test
    void aConfiguredFragmentIsDroppedRatherThanProducingTwo() {
        String url = SsoRedirects.successUrl(
                SsoPropertiesFixture.aProvider().loginSuccessUri("/app#/landing").build(),
                request("https", "bot.example.com", 443), "abc123", "otds");

        assertThat(url).startsWith("https://bot.example.com/app#sso_code=abc123");
    }

    @Test
    void aNonDefaultPortIsKeptOrTheRedirectLandsNowhere() {
        String url = SsoRedirects.successUrl(
                SsoPropertiesFixture.aProvider().build(), request("http", "localhost", 8080),
                "abc123", "otds");

        assertThat(url).startsWith("http://localhost:8080/sso/callback#sso_code=abc123");
    }

    @Test
    void anErrorMessageIsEncodedSoItsSpacesAndPunctuationSurviveTheUrl() {
        String url = SsoRedirects.errorUrl(
                SsoPropertiesFixture.aProvider().build(), request("https", "bot.example.com", 443),
                "This account has been disabled. Contact an administrator.");

        assertThat(url).startsWith("https://bot.example.com/sso/callback#sso_error=")
                .doesNotContain(" ")
                // A raw '#' or '&' in the message would truncate everything after it.
                .endsWith("This+account+has+been+disabled.+Contact+an+administrator.");
    }

    @Test
    void theProviderThatAnsweredTravelsWithTheCode() {
        // Signing out has to end the session at the provider it actually came from. A deployment
        // re-pointed at a different directory would otherwise send the next sign-out to the wrong
        // one, on the strength of a marker that only said "this was single sign-on".
        String url = SsoRedirects.successUrl(
                SsoPropertiesFixture.aProvider().providerId("entra").build(),
                request("https", "bot.example.com", 443), "abc123", "entra");

        assertThat(url).endsWith("#sso_code=abc123&sso_provider=entra");
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }
}
