package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

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

    /** Self-service, unlike email. Null until the user sets one — see {@code name IS NULL} gating. */
    @Column
    private String name;

    /** Set by an admin, typically at onboarding. Reporting only — never used for access control. */
    @Column(name = "business_unit")
    private String businessUnit;

    /**
     * A user's roles, not a role: {@link #setRoles} is the only mutator, and it always replaces
     * the whole set. Eagerly fetched because {@link #getAuthorities()} is read by the security
     * filter chain on every request, well outside any transaction the caller controls — a lazy
     * collection there fails with a {@code LazyInitializationException} instead of degrading.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<UserRole> roles = new LinkedHashSet<>(Set.of(UserRole.USER));

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
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return enabled; }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getBusinessUnit() { return businessUnit; }

    /** Read-only view; go through {@link #setRoles} to change membership. */
    public Set<UserRole> getRoles() { return Collections.unmodifiableSet(roles); }

    public boolean hasRole(UserRole role) { return roles.contains(role); }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public AuthProvider getAuthProvider() { return authProvider; }
    public String getSsoProviderId() { return ssoProviderId; }
    public String getExternalId() { return externalId; }
    public Instant getCreatedAt() { return createdAt; }

    /** True once this account can sign in through an identity provider. */
    public boolean isSsoLinked() { return externalId != null; }

    /** True when there is no password to change, verify or reset here. */
    public boolean hasNoLocalPassword() { return password == null || password.isBlank(); }

    /**
     * True once this account must sign in through SSO and password login is refused.
     *
     * <p>Once someone has signed in through the directory, they keep signing in that way — a
     * password left over from before is not a second front door. The one exception is an admin
     * who still holds a local password: that password stays a break-glass path around a
     * directory outage, so it must not become the same thing as losing the only way into the
     * admin screen.
     */
    public boolean isSsoOnly() {
        return isSsoLinked() && !(hasRole(UserRole.ADMIN) && !hasNoLocalPassword());
    }

    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public void setName(String name) { this.name = name; }
    public void setBusinessUnit(String businessUnit) { this.businessUnit = businessUnit; }

    /**
     * Replaces the full set of roles. A user with no roles could authenticate but do nothing —
     * that state is refused here rather than accepted and left for every caller to guard against.
     */
    public void setRoles(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("A user must have at least one role");
        }
        this.roles = new LinkedHashSet<>(roles);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public void setAuthProvider(AuthProvider authProvider) { this.authProvider = authProvider; }
    public void setSsoProviderId(String ssoProviderId) { this.ssoProviderId = ssoProviderId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
}
