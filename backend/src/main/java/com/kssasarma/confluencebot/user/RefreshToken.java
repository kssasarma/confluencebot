package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getToken() { return token; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }

    public void setUser(User user) { this.user = user; }
    public void setToken(String token) { this.token = token; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
