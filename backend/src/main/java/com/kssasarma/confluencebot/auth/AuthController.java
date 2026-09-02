package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.user.*;
import com.kssasarma.confluencebot.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authManager, JwtService jwtService,
                          UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                          PasswordEncoder passwordEncoder) {
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return ResponseEntity.ok(buildAuthResponse(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        RefreshToken rt = refreshTokenRepository.findByToken(req.refreshToken())
                .orElse(null);
        if (rt == null || rt.isRevoked() || rt.getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(401).body(new AuthResponse(null, null, null, null, null, false, "Invalid or expired refresh token"));
        }
        rt.setRevoked(true);
        refreshTokenRepository.save(rt);
        return ResponseEntity.ok(buildAuthResponse(rt.getUser()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        refreshTokenRepository.findByToken(req.refreshToken()).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserInfoResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new UserInfoResponse(
                user.getId(), user.getEmail(), user.getRole().name(), user.isMustChangePassword()));
    }

    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(@AuthenticationPrincipal User user,
                                                       @Valid @RequestBody ChangePasswordRequest req) {
        if (!passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            return ResponseEntity.status(400).body(new AuthResponse(null, null, null, null, null, false, "Current password is incorrect"));
        }
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
        return ResponseEntity.ok(buildAuthResponse(user));
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setToken(UUID.randomUUID().toString());
        rt.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        refreshTokenRepository.save(rt);
        return new AuthResponse(
                user.getId(), user.getEmail(), user.getRole().name(),
                token, rt.getToken(), user.isMustChangePassword(), null);
    }
}
