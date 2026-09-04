package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.security.JwtService;
import com.kssasarma.confluencebot.user.RefreshToken;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Mints the credential pair a signed-in client carries.
 *
 * <p>Extracted from {@link AuthServiceImpl} once single sign-on became a second way to arrive at a
 * session: a password login and a directory login differ entirely in how identity is established
 * and not at all in what is handed back afterwards, and the half they share is the half that must
 * not drift — an access token minted one way and a refresh token recorded the other is a session
 * that cannot be rotated.
 */
@Component
@Transactional
public class TokenIssuer {

    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration refreshTokenTtl;

    public TokenIssuer(JwtService jwtService,
                       RefreshTokenRepository refreshTokenRepository,
                       @Value("${app.jwt.refresh-token-ttl:P30D}") Duration refreshTokenTtl) {
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public AuthResponse issue(User user) {
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
