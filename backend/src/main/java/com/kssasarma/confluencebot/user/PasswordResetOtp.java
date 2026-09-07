package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A one-time code for the self-service "forgot password" flow, scoped to the user it was issued
 * for. {@link #otpHash} is a bcrypt hash of the 6-digit code, the same way a password is stored —
 * a 6-digit space is brute-forceable in the clear, unlike the high-entropy random token
 * {@link RefreshToken} gets away with comparing directly.
 */
@Entity
@Table(name = "password_reset_otps")
public class PasswordResetOtp {

    /** A code is dead after this many wrong guesses, however long it has left before expiry. */
    public static final int MAX_ATTEMPTS = 5;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getOtpHash() { return otpHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts() { return attempts; }
    public boolean isConsumed() { return consumed; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUser(User user) { this.user = user; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }

    public void recordFailedAttempt() { this.attempts++; }

    public boolean isUsable(Instant now) {
        return !consumed && attempts < MAX_ATTEMPTS && expiresAt.isAfter(now);
    }
}
