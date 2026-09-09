package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.email.EmailService;
import com.kssasarma.confluencebot.exception.InvalidRefreshTokenException;
import com.kssasarma.confluencebot.exception.SsoOnlyAccountException;
import com.kssasarma.confluencebot.security.JwtService;
import com.kssasarma.confluencebot.user.PasswordResetOtp;
import com.kssasarma.confluencebot.user.PasswordResetOtpRepository;
import com.kssasarma.confluencebot.user.RefreshToken;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The role claim moved from a single enum to a sorted, multi-valued list; these pin that every
 * flow that mints a token ({@code login}, {@code refresh}, {@code changePassword}) still reports
 * every role a user holds, not just one of them.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordResetOtpRepository otpRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        // A real TokenIssuer over the mocked collaborators: minting moved out of AuthServiceImpl
        // when single sign-on became a second way to reach a session, and these assertions are
        // about what comes back from a flow — which is exactly what the issuer builds.
        service = new AuthServiceImpl(authenticationManager, userRepository,
                refreshTokenRepository, otpRepository, emailService, passwordEncoder,
                new TokenIssuer(jwtService, refreshTokenRepository, Duration.ofDays(30)),
                Duration.ofMinutes(10));
    }

    private static User userWithRoles(Long id, String email, Set<UserRole> roles) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("hashed");
        user.setRoles(roles);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void login_validCredentials_returnsEverySortedRoleName() {
        User user = userWithRoles(1L, "multi@example.com", Set.of(UserRole.USER, UserRole.INGESTOR));
        when(userRepository.findByEmail("multi@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("access-token");

        AuthResponse response = service.login(new LoginRequest("multi@example.com", "secret"));

        assertThat(response.roles()).containsExactly("INGESTOR", "USER");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.token()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotBlank();
        verify(authenticationManager).authenticate(any());
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void login_authenticatedEmailMissingFromRepository_throwsBadCredentials() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@example.com", "secret")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_ssoLinkedRegularUser_isRefusedBeforeCredentialsAreEvenChecked() {
        User user = userWithRoles(15L, "jane@example.com", Set.of(UserRole.USER));
        user.setSsoProviderId("otds");
        user.setExternalId("subject-1");
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("jane@example.com", "whatever")))
                .isInstanceOf(SsoOnlyAccountException.class)
                .hasMessageContaining("single sign-on");
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void login_ssoLinkedAdminWhoStillHoldsAPassword_isAllowedAsABreakGlassPath() {
        User admin = userWithRoles(16L, "admin@example.com", Set.of(UserRole.ADMIN));
        admin.setSsoProviderId("otds");
        admin.setExternalId("subject-admin");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(jwtService.generateToken(admin)).thenReturn("admin-token");

        AuthResponse response = service.login(new LoginRequest("admin@example.com", "secret"));

        assertThat(response.token()).isEqualTo("admin-token");
        verify(authenticationManager).authenticate(any());
    }

    @Test
    void refresh_validToken_revokesTheOldOneAndIssuesRolesForTheOwningUser() {
        User user = userWithRoles(2L, "admin@example.com", Set.of(UserRole.ADMIN));
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setToken("old-refresh");
        stored.setExpiresAt(Instant.now().plusSeconds(60));
        stored.setRevoked(false);

        when(refreshTokenRepository.findByTokenWithUser("old-refresh")).thenReturn(Optional.of(stored));
        when(jwtService.generateToken(user)).thenReturn("new-access-token");

        AuthResponse response = service.refresh(new RefreshRequest("old-refresh"));

        assertThat(stored.isRevoked()).isTrue();
        assertThat(response.roles()).containsExactly("ADMIN");
        assertThat(response.token()).isEqualTo("new-access-token");
    }

    @Test
    void refresh_expiredToken_throwsInvalidRefreshToken() {
        User user = userWithRoles(3L, "user@example.com", Set.of(UserRole.USER));
        RefreshToken expired = new RefreshToken();
        expired.setUser(user);
        expired.setToken("expired");
        expired.setExpiresAt(Instant.now().minusSeconds(1));

        when(refreshTokenRepository.findByTokenWithUser("expired")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.refresh(new RefreshRequest("expired")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void refresh_revokedToken_throwsInvalidRefreshToken() {
        User user = userWithRoles(4L, "user@example.com", Set.of(UserRole.USER));
        RefreshToken revoked = new RefreshToken();
        revoked.setUser(user);
        revoked.setToken("revoked");
        revoked.setExpiresAt(Instant.now().plusSeconds(60));
        revoked.setRevoked(true);

        when(refreshTokenRepository.findByTokenWithUser("revoked")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.refresh(new RefreshRequest("revoked")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_unknownToken_throwsInvalidRefreshToken() {
        when(refreshTokenRepository.findByTokenWithUser("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(new RefreshRequest("missing")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logout_existingToken_marksItRevoked() {
        RefreshToken stored = new RefreshToken();
        stored.setToken("to-revoke");
        stored.setRevoked(false);
        when(refreshTokenRepository.findByToken("to-revoke")).thenReturn(Optional.of(stored));

        service.logout(new RefreshRequest("to-revoke"));

        assertThat(stored.isRevoked()).isTrue();
    }

    @Test
    void logout_unknownToken_doesNothing() {
        when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        service.logout(new RefreshRequest("missing"));
        // No exception, and nothing to assert on: a logout for a token that no longer exists is
        // already the state the caller wanted.
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsBadCredentialsAndKeepsExistingTokensAlive() {
        User managed = userWithRoles(5L, "user@example.com", Set.of(UserRole.USER));
        managed.setPassword("hashed-current");
        when(userRepository.findById(5L)).thenReturn(Optional.of(managed));
        when(passwordEncoder.matches("wrong", "hashed-current")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(managed,
                new ChangePasswordRequest("wrong", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void changePassword_correctCurrentPassword_updatesPasswordAndRevokesEveryToken() {
        User managed = userWithRoles(6L, "user@example.com", Set.of(UserRole.USER));
        managed.setPassword("hashed-current");
        managed.setMustChangePassword(true);
        when(userRepository.findById(6L)).thenReturn(Optional.of(managed));
        when(passwordEncoder.matches("current", "hashed-current")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("hashed-new");
        when(jwtService.generateToken(managed)).thenReturn("post-change-token");

        AuthResponse response = service.changePassword(managed,
                new ChangePasswordRequest("current", "newPassword1"));

        ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
        verify(refreshTokenRepository).revokeAllByUserId(userId.capture());
        assertThat(userId.getValue()).isEqualTo(6L);
        assertThat(managed.getPassword()).isEqualTo("hashed-new");
        assertThat(managed.isMustChangePassword()).isFalse();
        assertThat(response.token()).isEqualTo("post-change-token");
        assertThat(response.roles()).containsExactly("USER");
    }

    // ── updateName ───────────────────────────────────────────────────────────

    @Test
    void updateName_setsTrimmedNameAndReturnsIt() {
        User managed = userWithRoles(7L, "user@example.com", Set.of(UserRole.USER));
        when(userRepository.findById(7L)).thenReturn(Optional.of(managed));

        UserInfoResponse response = service.updateName(managed, new UpdateNameRequest("  Ada Lovelace  "));

        assertThat(managed.getName()).isEqualTo("Ada Lovelace");
        assertThat(response.name()).isEqualTo("Ada Lovelace");
        assertThat(response.email()).isEqualTo("user@example.com");
    }

    @Test
    void updateName_replacesAnyExistingName() {
        User managed = userWithRoles(8L, "user@example.com", Set.of(UserRole.USER));
        managed.setName("Old Name");
        when(userRepository.findById(8L)).thenReturn(Optional.of(managed));

        service.updateName(managed, new UpdateNameRequest("New Name"));

        assertThat(managed.getName()).isEqualTo("New Name");
    }

    // ── requestPasswordReset ─────────────────────────────────────────────────

    @Test
    void requestPasswordReset_knownEmail_emailsACodeAndInvalidatesEarlierOnes() {
        User user = userWithRoles(9L, "user@example.com", Set.of(UserRole.USER));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(emailService.sendPasswordResetOtp(eq("user@example.com"), anyString(), eq(10))).thenReturn(true);

        boolean result = service.requestPasswordReset(new ForgotPasswordRequest("user@example.com"));

        assertThat(result).isTrue();
        verify(otpRepository).consumeAllByUserId(9L);
        ArgumentCaptor<PasswordResetOtp> saved = ArgumentCaptor.forClass(PasswordResetOtp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().getOtpHash()).isEqualTo("hashed-otp");
        assertThat(saved.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void requestPasswordReset_unknownEmail_reportsSuccessWithoutEmailingAnything() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        boolean result = service.requestPasswordReset(new ForgotPasswordRequest("ghost@example.com"));

        assertThat(result).isTrue();
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), anyInt());
        verify(otpRepository, never()).save(any());
    }

    @Test
    void requestPasswordReset_mailFails_reportsFalse() {
        User user = userWithRoles(9L, "user@example.com", Set.of(UserRole.USER));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(emailService.sendPasswordResetOtp(any(), any(), anyInt())).thenReturn(false);

        boolean result = service.requestPasswordReset(new ForgotPasswordRequest("user@example.com"));

        assertThat(result).isFalse();
    }

    // ── resetPassword ────────────────────────────────────────────────────────

    private static PasswordResetOtp usableOtp(User user, String hash) {
        PasswordResetOtp otp = new PasswordResetOtp();
        otp.setUser(user);
        otp.setOtpHash(hash);
        otp.setExpiresAt(Instant.now().plusSeconds(300));
        return otp;
    }

    @Test
    void resetPassword_correctCode_updatesPasswordAndSignsIn() {
        User user = userWithRoles(10L, "user@example.com", Set.of(UserRole.USER));
        user.setMustChangePassword(true);
        PasswordResetOtp otp = usableOtp(user, "hashed-otp");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(10L)).thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("123456", "hashed-otp")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("hashed-new");
        when(jwtService.generateToken(user)).thenReturn("post-reset-token");

        AuthResponse response = service.resetPassword(
                new ResetPasswordRequest("user@example.com", "123456", "newPassword1"));

        assertThat(otp.isConsumed()).isTrue();
        assertThat(user.getPassword()).isEqualTo("hashed-new");
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(response.token()).isEqualTo("post-reset-token");
        verify(refreshTokenRepository).revokeAllByUserId(10L);
    }

    @Test
    void resetPassword_wrongCode_throwsAndRecordsTheAttempt() {
        User user = userWithRoles(11L, "user@example.com", Set.of(UserRole.USER));
        PasswordResetOtp otp = usableOtp(user, "hashed-otp");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(11L)).thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("000000", "hashed-otp")).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("user@example.com", "000000", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(otp.getAttempts()).isEqualTo(1);
        assertThat(otp.isConsumed()).isFalse();
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void resetPassword_expiredCode_throwsBadCredentials() {
        User user = userWithRoles(12L, "user@example.com", Set.of(UserRole.USER));
        PasswordResetOtp otp = usableOtp(user, "hashed-otp");
        ReflectionTestUtils.setField(otp, "expiresAt", Instant.now().minusSeconds(1));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("user@example.com", "123456", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void resetPassword_tooManyAttempts_throwsBadCredentialsEvenWithTheRightCode() {
        User user = userWithRoles(13L, "user@example.com", Set.of(UserRole.USER));
        PasswordResetOtp otp = usableOtp(user, "hashed-otp");
        for (int i = 0; i < PasswordResetOtp.MAX_ATTEMPTS; i++) otp.recordFailedAttempt();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(13L)).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("user@example.com", "123456", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resetPassword_noCodeEverRequested_throwsBadCredentials() {
        User user = userWithRoles(14L, "user@example.com", Set.of(UserRole.USER));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(14L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("user@example.com", "123456", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resetPassword_ssoOnlyAccountWithAStaleValidOtp_isStillRefused() {
        // The OTP could be genuine and unexpired if the account linked SSO after it was issued —
        // it still resets nothing this account can use, so it is treated the same as an invalid one.
        User migrated = userWithRoles(17L, "jane@corp.example", Set.of(UserRole.USER));
        migrated.setSsoProviderId("otds");
        migrated.setExternalId("subject-1");
        when(userRepository.findByEmail("jane@corp.example")).thenReturn(Optional.of(migrated));

        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("jane@corp.example", "123456", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);
        verify(otpRepository, never()).findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(any());
    }

    @Test
    void resetPassword_unknownEmail_throwsBadCredentials() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("ghost@example.com", "123456", "newPassword1")))
                .isInstanceOf(BadCredentialsException.class);
    }
}
