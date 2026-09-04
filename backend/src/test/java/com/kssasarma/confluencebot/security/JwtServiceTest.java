package com.kssasarma.confluencebot.security;

import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET =
            "unit-test-signing-secret-at-least-256-bits-long-for-hmac-sha-algorithms";

    private final JwtService jwtService = new JwtService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMs", 900_000L);
    }

    private static User userWithRoles(Long id, String email, Set<UserRole> roles) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(roles);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Claims decode(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    @Test
    void generateToken_encodesEverySortedRoleNameAndTheSubject() {
        User user = userWithRoles(7L, "multi@example.com", Set.of(UserRole.USER, UserRole.INGESTOR));

        Claims claims = decode(jwtService.generateToken(user));

        assertThat(claims.getSubject()).isEqualTo("multi@example.com");
        assertThat(((Number) claims.get("userId")).longValue()).isEqualTo(7L);
        assertThat(claims.get("roles", List.class)).containsExactly("INGESTOR", "USER");
        assertThat((Boolean) claims.get("mustChangePassword")).isFalse();
    }

    @Test
    void generateToken_singleRoleUser_encodesOneRole() {
        User user = userWithRoles(8L, "solo@example.com", Set.of(UserRole.ADMIN));

        Claims claims = decode(jwtService.generateToken(user));

        assertThat(claims.get("roles", List.class)).containsExactly("ADMIN");
    }

    @Test
    void extractEmail_returnsTheTokenSubject() {
        User user = userWithRoles(9L, "reader@example.com", Set.of(UserRole.USER));

        String email = jwtService.extractEmail(jwtService.generateToken(user));

        assertThat(email).isEqualTo("reader@example.com");
    }

    @Test
    void isTokenValid_unexpiredToken_returnsTrue() {
        User user = userWithRoles(10L, "reader@example.com", Set.of(UserRole.USER));

        assertThat(jwtService.isTokenValid(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void isTokenValid_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtService, "expirationMs", -1_000L);
        User user = userWithRoles(11L, "reader@example.com", Set.of(UserRole.USER));

        assertThat(jwtService.isTokenValid(jwtService.generateToken(user))).isFalse();
    }

    @Test
    void isTokenValid_malformedToken_returnsFalse() {
        assertThat(jwtService.isTokenValid("not-a-real-token")).isFalse();
    }
}
