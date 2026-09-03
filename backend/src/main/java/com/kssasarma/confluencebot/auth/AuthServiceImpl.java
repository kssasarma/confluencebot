package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.exception.InvalidRefreshTokenException;
import com.kssasarma.confluencebot.user.RefreshToken;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * All four flows run inside a transaction, which is what the refresh flow needs: the refresh token
 * carries a lazily-loaded user, and reading it outside a session is exactly what used to blow up
 * with a LazyInitializationException.
 */
@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;

    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordEncoder passwordEncoder,
                           TokenIssuer tokenIssuer) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        return tokenIssuer.issue(user);
    }

    @Override
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken token = refreshTokenRepository.findByTokenWithUser(request.refreshToken())
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid or expired refresh token"));

        if (token.isRevoked() || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        }

        token.setRevoked(true);
        return tokenIssuer.issue(token.getUser());
    }

    @Override
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByToken(request.refreshToken())
                .ifPresent(token -> token.setRevoked(true));
    }

    @Override
    public AuthResponse changePassword(User user, ChangePasswordRequest request) {
        User managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // An OTDS-provisioned account has no password here to be current, new, or wrong. Saying so
        // is kinder than the "current password is incorrect" every attempt would otherwise get,
        // and it is the whole reason the change-password wall is not shown to those accounts.
        if (managed.hasNoLocalPassword()) {
            throw new IllegalArgumentException(
                    "This account signs in through your identity provider and has no password to change.");
        }

        if (!passwordEncoder.matches(request.currentPassword(), managed.getPassword())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        managed.setPassword(passwordEncoder.encode(request.newPassword()));
        managed.setMustChangePassword(false);

        // Every previously issued refresh token dies with the old password.
        refreshTokenRepository.revokeAllByUserId(managed.getId());

        return tokenIssuer.issue(managed);
    }
}
