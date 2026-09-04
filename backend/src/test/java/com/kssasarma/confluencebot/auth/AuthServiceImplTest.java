package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.exception.InvalidRefreshTokenException;
import com.kssasarma.confluencebot.security.JwtService;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Mock private PasswordEncoder passwordEncoder;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(authenticationManager, jwtService, userRepository,
                refreshTokenRepository, passwordEncoder, Duration.ofDays(30));
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
}
