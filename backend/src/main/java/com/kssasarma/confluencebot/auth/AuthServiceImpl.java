package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.email.EmailService;
import com.kssasarma.confluencebot.exception.InvalidRefreshTokenException;
import com.kssasarma.confluencebot.security.JwtService;
import com.kssasarma.confluencebot.user.PasswordResetOtp;
import com.kssasarma.confluencebot.user.PasswordResetOtpRepository;
import com.kssasarma.confluencebot.user.RefreshToken;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * All four flows run inside a transaction, which is what the refresh flow needs: the refresh token
 * carries a lazily-loaded user, and reading it outside a session is exactly what used to blow up
 * with a LazyInitializationException.
 */
@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** A code is six digits — easy to read out or type on a phone, hard enough to guess when
     * paired with {@link PasswordResetOtp#MAX_ATTEMPTS} capping the tries against any one code. */
    private static final int OTP_DIGITS = 6;

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetOtpRepository otpRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final Duration refreshTokenTtl;
    private final Duration otpTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           JwtService jwtService,
                           UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordResetOtpRepository otpRepository,
                           EmailService emailService,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.jwt.refresh-token-ttl:P30D}") Duration refreshTokenTtl,
                           @Value("${app.otp.ttl:PT10M}") Duration otpTtl) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpRepository = otpRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenTtl = refreshTokenTtl;
        this.otpTtl = otpTtl;
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

    @Override
    public UserInfoResponse updateName(User user, UpdateNameRequest request) {
        User managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        managed.setName(request.name());
        userRepository.save(managed);

        return new UserInfoResponse(managed.getId(), managed.getEmail(), managed.getName(),
                UserRole.namesOf(managed.getRoles()), managed.isMustChangePassword());
    }

    @Override
    public boolean requestPasswordReset(ForgotPasswordRequest request) {
        Optional<User> user = userRepository.findByEmail(request.email());
        if (user.isEmpty()) {
            // Not reported as an error: confirming an email is unregistered is exactly the
            // enumeration this stays silent about. The caller sees the same "check your email"
            // outcome either way.
            log.debug("Password reset requested for an unknown email");
            return true;
        }

        // Only the most recently issued code is ever valid — a reader who asks twice should not
        // be left guessing which of two emails is current.
        otpRepository.consumeAllByUserId(user.get().getId());

        String otp = generateOtp();
        PasswordResetOtp entity = new PasswordResetOtp();
        entity.setUser(user.get());
        entity.setOtpHash(passwordEncoder.encode(otp));
        entity.setExpiresAt(Instant.now().plus(otpTtl));
        otpRepository.save(entity);

        boolean sent = emailService.sendPasswordResetOtp(user.get().getEmail(), otp, (int) otpTtl.toMinutes());
        if (!sent) {
            log.warn("Could not email a password reset code to {}", user.get().getEmail());
        }
        return sent;
    }

    @Override
    public AuthResponse resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired code"));

        PasswordResetOtp otp = otpRepository
                .findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired code"));

        if (!otp.isUsable(Instant.now())) {
            throw new BadCredentialsException("Invalid or expired code");
        }

        if (!passwordEncoder.matches(request.otp(), otp.getOtpHash())) {
            otp.recordFailedAttempt();
            throw new BadCredentialsException("Invalid or expired code");
        }

        otp.setConsumed(true);

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);

        // Every previously issued refresh token dies with the old password, same as a self-service
        // change — a session left open on a device that just lost its owner's password should not
        // survive that password being reset out from under it.
        refreshTokenRepository.revokeAllByUserId(user.getId());

        return issueTokens(user);
    }

    private String generateOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        int value = secureRandom.nextInt(bound);
        return String.format("%0" + OTP_DIGITS + "d", value);
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
                user.getId(), user.getEmail(), user.getName(), UserRole.namesOf(user.getRoles()),
                accessToken, refreshToken.getToken(), user.isMustChangePassword(), null);
    }
}
