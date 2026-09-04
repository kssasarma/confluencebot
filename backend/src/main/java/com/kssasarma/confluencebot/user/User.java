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

    @Column(nullable = false)
    private String password;

    /** Self-service, unlike email. Null until the user sets one — see {@code name IS NULL} gating. */
    @Column
    private String name;

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

    /** Read-only view; go through {@link #setRoles} to change membership. */
    public Set<UserRole> getRoles() { return Collections.unmodifiableSet(roles); }

    public boolean hasRole(UserRole role) { return roles.contains(role); }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public void setName(String name) { this.name = name; }

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
}
