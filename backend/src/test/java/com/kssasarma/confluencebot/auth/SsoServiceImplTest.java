package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.config.SsoPropertiesFixture;
import com.kssasarma.confluencebot.exception.InvalidSsoCodeException;
import com.kssasarma.confluencebot.user.SsoLoginCode;
import com.kssasarma.confluencebot.user.SsoLoginCodeRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hand-off code: what is written down, and what it takes to redeem it.
 *
 * <p>Two properties carry the whole design. The code is stored only as a hash, so a database dump
 * — or a stray log line printing a row — is not a set of usable sign-ins. And redemption is a
 * conditional update rather than a read followed by a write, so two requests arriving with the
 * same code cannot both be told yes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoServiceImplTest {

    @Mock private SsoLoginCodeRepository loginCodeRepository;
    @Mock private TokenIssuer tokenIssuer;

    private SsoServiceImpl service;

    @BeforeEach
    void setUp() {
        when(loginCodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new SsoServiceImpl(SsoPropertiesFixture.aProvider().build(),
                loginCodeRepository, tokenIssuer);
    }

    @Test
    void aDisabledDeploymentAdvertisesNothingAtAll() {
        SsoServiceImpl disabled = new SsoServiceImpl(
                SsoPropertiesFixture.aProvider().enabled(false).build(), loginCodeRepository, tokenIssuer);

        SsoStatusResponse status = disabled.describe();

        assertThat(status.enabled()).isFalse();
        // Not even the provider's name: a signed-out visitor has not earned a description of the
        // directory this deployment happens to sit behind.
        assertThat(status.providerName()).isNull();
        assertThat(status.authorizationUrl()).isNull();
    }

    @Test
    void anEnabledDeploymentAdvertisesTheNameAndWhereToStart() {
        SsoStatusResponse status = service.describe();

        assertThat(status.enabled()).isTrue();
        assertThat(status.providerName()).isEqualTo("OpenText");
        assertThat(status.authorizationUrl()).isEqualTo("/api/oauth2/authorization/otds");
        assertThat(status.logoutUrl()).isNull();
    }

    @Test
    void aConfiguredEndSessionEndpointIsPassedOnSoSigningOutReachesTheProvider() {
        SsoServiceImpl withLogout = new SsoServiceImpl(
                SsoPropertiesFixture.aProvider().logoutUri("https://otds.example.com/otdsws/logout").build(),
                loginCodeRepository, tokenIssuer);

        assertThat(withLogout.describe().logoutUrl()).isEqualTo("https://otds.example.com/otdsws/logout");
    }

    @Test
    void onlyTheHashOfTheCodeIsEverWrittenDown() {
        User user = user("jane@corp.example");

        String code = service.issueLoginCode(user);

        ArgumentCaptor<SsoLoginCode> saved = ArgumentCaptor.forClass(SsoLoginCode.class);
        verify(loginCodeRepository).save(saved.capture());
        assertThat(saved.getValue().getCodeHash())
                .isEqualTo(sha256Hex(code))
                .isNotEqualTo(code);
        assertThat(saved.getValue().getUser()).isSameAs(user);
    }

    @Test
    void aFreshCodeIsHandedOutEveryTime() {
        User user = user("jane@corp.example");

        assertThat(service.issueLoginCode(user)).isNotEqualTo(service.issueLoginCode(user));
    }

    @Test
    void issuingACodeAlsoClearsOutTheOnesThatHaveExpired() {
        // The table would otherwise grow one row per sign-in forever, and this application runs no
        // scheduler to sweep it.
        service.issueLoginCode(user("jane@corp.example"));

        verify(loginCodeRepository).deleteExpiredBefore(any(Instant.class));
    }

    @Test
    void theCodeIsShortLivedByConfiguration() {
        SsoServiceImpl brief = new SsoServiceImpl(
                SsoPropertiesFixture.aProvider().codeTtl(Duration.ofSeconds(30)).build(),
                loginCodeRepository, tokenIssuer);

        Instant before = Instant.now();
        brief.issueLoginCode(user("jane@corp.example"));

        ArgumentCaptor<SsoLoginCode> saved = ArgumentCaptor.forClass(SsoLoginCode.class);
        verify(loginCodeRepository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isBetween(before, before.plusSeconds(31));
    }

    @Test
    void redeemingACodeIssuesTheSameTokenPairAPasswordSignInWould() {
        User user = user("jane@corp.example");
        AuthResponse expected = new AuthResponse(7L, "jane@corp.example", "USER", "jwt", "refresh", false, null);
        when(loginCodeRepository.consume(anyString(), any(Instant.class))).thenReturn(1);
        when(loginCodeRepository.findByCodeHashWithUser(anyString())).thenReturn(Optional.of(codeFor(user)));
        when(tokenIssuer.issue(user)).thenReturn(expected);

        assertThat(service.exchangeLoginCode("a-code")).isSameAs(expected);
    }

    @Test
    void aCodeIsLookedUpByItsHashAndNeverByItsValue() {
        User user = user("jane@corp.example");
        when(loginCodeRepository.consume(anyString(), any(Instant.class))).thenReturn(1);
        when(loginCodeRepository.findByCodeHashWithUser(anyString())).thenReturn(Optional.of(codeFor(user)));

        service.exchangeLoginCode("a-code");

        verify(loginCodeRepository).consume(eq(sha256Hex("a-code")), any(Instant.class));
        verify(loginCodeRepository).findByCodeHashWithUser(sha256Hex("a-code"));
    }

    @Test
    void aCodeTheDatabaseDeclinesToConsumeBuysNothing() {
        // Unknown, already redeemed, or past its minute — the conditional update matched no row,
        // and this side does not get to second-guess which of the three it was.
        when(loginCodeRepository.consume(anyString(), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service.exchangeLoginCode("a-code"))
                .isInstanceOf(InvalidSsoCodeException.class);
        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void anAccountDisabledBetweenSigningInAndLandingGetsNoSession() {
        User user = user("jane@corp.example");
        user.setEnabled(false);
        when(loginCodeRepository.consume(anyString(), any(Instant.class))).thenReturn(1);
        when(loginCodeRepository.findByCodeHashWithUser(anyString())).thenReturn(Optional.of(codeFor(user)));

        assertThatThrownBy(() -> service.exchangeLoginCode("a-code"))
                .isInstanceOf(InvalidSsoCodeException.class)
                .hasMessageContaining("disabled");
        verify(tokenIssuer, never()).issue(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setRole(UserRole.USER);
        return user;
    }

    private static SsoLoginCode codeFor(User user) {
        SsoLoginCode code = new SsoLoginCode();
        code.setUser(user);
        code.setExpiresAt(Instant.now().plusSeconds(60));
        return code;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
