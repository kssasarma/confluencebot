package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The hand-off between the end of a single sign-on and the single-page app.
 *
 * <p>A provider finishes by redirecting a browser, and a redirect can only carry what fits in a URL —
 * but a URL is the one place an access and refresh token pair must not be: it survives in browser
 * history, and in the {@code Referer} of the next request the page makes. So the redirect carries
 * this instead: a random, single-use, one-minute credential that buys exactly one token pair over
 * a POST the page makes itself.
 *
 * <p>Only the SHA-256 of the code is stored. A row read out of the database is not a credential.
 */
@Entity
@Table(name = "sso_login_codes")
public class SsoLoginCode {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public User getUser() { return user; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isConsumed() { return consumed; }

    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public void setUser(User user) { this.user = user; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }
}
