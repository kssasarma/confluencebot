package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
public class User implements UserDetails {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /**
     * BCrypt hash, or null for an account that has no password here at all.
     *
     * <p>Null is how a directory-provisioned account is represented: there is nothing to verify
     * locally, and nothing to leak. A password sign-in attempt against one still runs the normal
     * path and fails as bad credentials, because no raw password matches an absent hash.
     */
    @Column
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /** Where the account came from — see {@link AuthProvider}. Never changes after creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    /**
     * Which identity provider {@link #externalId} belongs to — the configured
     * {@code app.sso.provider-id}, or null for an account that has never signed in that way.
     *
     * <p>Stored rather than assumed because a subject is only unique within the provider that
     * issued it: two directories can both call somebody {@code 12345}, and a deployment that
     * changes provider must not seat the new directory's users in the old one's accounts.
     */
    @Column(name = "sso_provider_id", length = 64)
    private String ssoProviderId;

    /**
     * The subject this account is linked to, or null if it has never signed in through a provider.
     *
     * <p>Keyed on the subject rather than the address because a directory can rename a mailbox
     * without it becoming a different person, and because two identities must never collapse into
     * one account when an address is reassigned.
     */
    @Column(name = "external_id")
    private String externalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return enabled; }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public UserRole getRole() { return role; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public AuthProvider getAuthProvider() { return authProvider; }
    public String getSsoProviderId() { return ssoProviderId; }
    public String getExternalId() { return externalId; }
    public Instant getCreatedAt() { return createdAt; }

    /** True once this account can sign in through an identity provider. */
    public boolean isSsoLinked() { return externalId != null; }

    /** True when there is no password to change, verify or reset here. */
    public boolean hasNoLocalPassword() { return password == null || password.isBlank(); }

    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public void setRole(UserRole role) { this.role = role; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public void setAuthProvider(AuthProvider authProvider) { this.authProvider = authProvider; }
    public void setSsoProviderId(String ssoProviderId) { this.ssoProviderId = ssoProviderId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
}
