package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.email.EmailService;
import com.kssasarma.confluencebot.user.AuthProvider;
import com.kssasarma.confluencebot.user.PasswordResetOtpRepository;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the password flows do about an account that has no password.
 *
 * <p>Single sign-on introduced a row in {@code users} with a null hash, and every path that
 * previously assumed one is now a path that has to say something sensible instead. The encoder
 * behaviour asserted at the bottom is the load-bearing one: it is what makes a null hash
 * unmatchable rather than matchable-by-anything, and it is a property of Spring's encoder rather
 * than of this codebase — worth a test precisely because nothing here would catch it changing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplPasswordlessTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordResetOtpRepository otpRepository;
    @Mock private EmailService emailService;
    @Mock private TokenIssuer tokenIssuer;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(authenticationManager, userRepository, refreshTokenRepository,
                otpRepository, emailService, passwordEncoder, tokenIssuer, Duration.ofMinutes(10));
    }

    @Test
    void changingThePasswordOfADirectoryAccountSaysWhyItCannotBeDone() {
        User ssoOnly = new User();
        ssoOnly.setEmail("jane@corp.example");
        ssoOnly.setRoles(Set.of(UserRole.USER));
        ssoOnly.setAuthProvider(AuthProvider.SSO);
        ssoOnly.setSsoProviderId("otds");
        ssoOnly.setExternalId("a-subject");
        ssoOnly.setPassword(null);
        when(userRepository.findById(any())).thenReturn(Optional.of(ssoOnly));

        assertThatThrownBy(() -> service.changePassword(ssoOnly,
                new ChangePasswordRequest("whatever", "NewPassword1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity provider");

        // No password set, and no sessions torn down on the way to a failure.
        assertThat(ssoOnly.getPassword()).isNull();
        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void changingThePasswordOfARegularAccountThatKeptItsPasswordAfterLinkingSsoSaysWhyItCannotBeDone() {
        // The password survives linking so an admin has a break-glass path — but for a regular
        // account it is no longer a valid way in, so there is nothing here to change either.
        User migrated = new User();
        migrated.setEmail("jane@corp.example");
        migrated.setRoles(Set.of(UserRole.USER));
        migrated.setAuthProvider(AuthProvider.LOCAL);
        migrated.setPassword("still-here-from-before-linking");
        migrated.setSsoProviderId("otds");
        migrated.setExternalId("subject-1");
        when(userRepository.findById(any())).thenReturn(Optional.of(migrated));

        assertThatThrownBy(() -> service.changePassword(migrated,
                new ChangePasswordRequest("whatever", "NewPassword1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity provider");

        assertThat(migrated.getPassword()).isEqualTo("still-here-from-before-linking");
        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void requestingAPasswordResetForAnSsoOnlyAccountReportsSuccessWithoutEmailingAnything() {
        User migrated = new User();
        migrated.setEmail("jane@corp.example");
        migrated.setRoles(Set.of(UserRole.USER));
        migrated.setPassword("still-here-from-before-linking");
        migrated.setSsoProviderId("otds");
        migrated.setExternalId("subject-1");
        when(userRepository.findByEmail("jane@corp.example")).thenReturn(Optional.of(migrated));

        boolean result = service.requestPasswordReset(new ForgotPasswordRequest("jane@corp.example"));

        // Same silent "check your email" outcome as an unregistered address, on purpose: whether
        // an account is SSO-only is not this endpoint's business to reveal either.
        assertThat(result).isTrue();
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(otpRepository, never()).save(any());
    }

    @Test
    void anAbsentHashIsUnmatchableRatherThanMatchedByAnything() {
        // This is what makes a password sign-in against a directory-only account fail as bad
        // credentials instead of succeeding: DaoAuthenticationProvider asks exactly this question.
        assertThat(passwordEncoder.matches("", null)).isFalse();
        assertThat(passwordEncoder.matches("any password at all", null)).isFalse();
    }
}
