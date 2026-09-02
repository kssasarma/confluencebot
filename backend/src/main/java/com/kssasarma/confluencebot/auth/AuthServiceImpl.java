package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.exception.InvalidRefreshTokenException;
import com.kssasarma.confluencebot.security.JwtService;
import com.kssasarma.confluencebot.user.RefreshToken;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * All four flows run inside a transaction, which is what the refresh flow needs: the refresh token
 * carries a lazily-loaded user, and reading it outside a session is exactly what used to blow up
 * with a LazyInitializationException.
 */
@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Duration refreshTokenTtl;

    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           JwtService jwtService,
                           UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.jwt.refresh-token-ttl:P30D}") Duration refreshTokenTtl) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        return issueTokens(user);
    }

    @Override
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken token = refreshTokenRepository.findByTokenWithUser(request.refreshToken())
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid or expired refresh token"));

        if (token.isRevoked() || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        }

        token.setRevoked(true);
        return issueTokens(token.getUser());
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

        if (!passwordEncoder.matches(request.currentPassword(), managed.getPassword())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        managed.setPassword(passwordEncoder.encode(request.newPassword()));
        managed.setMustChangePassword(false);

        // Every previously issued refresh token dies with the old password.
        refreshTokenRepository.revokeAllByUserId(managed.getId());

        return issueTokens(managed);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateToken(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setExpiresAt(Instant.now().plus(refreshTokenTtl));
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                user.getId(), user.getEmail(), user.getRole().name(),
                accessToken, refreshToken.getToken(), user.isMustChangePassword(), null);
    }
}
