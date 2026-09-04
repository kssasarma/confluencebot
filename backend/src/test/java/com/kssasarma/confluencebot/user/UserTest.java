package com.kssasarma.confluencebot.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    void newUser_defaultsToSingleUserRole() {
        User user = new User();

        assertThat(user.getRoles()).containsExactly(UserRole.USER);
    }

    @Test
    void getAuthorities_mapsEachRoleToASpringSecurityRoleAuthority() {
        User user = new User();
        user.setRoles(Set.of(UserRole.ADMIN, UserRole.INGESTOR));

        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_INGESTOR");
    }

    @Test
    void hasRole_roleInSet_returnsTrue() {
        User user = new User();
        user.setRoles(Set.of(UserRole.INGESTOR));

        assertThat(user.hasRole(UserRole.INGESTOR)).isTrue();
        assertThat(user.hasRole(UserRole.ADMIN)).isFalse();
    }

    @Test
    void setRoles_null_throwsIllegalArgumentException() {
        User user = new User();

        assertThatThrownBy(() -> user.setRoles(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRoles_empty_throwsIllegalArgumentException() {
        User user = new User();

        assertThatThrownBy(() -> user.setRoles(Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRoles_replacesThePreviousSetEntirely() {
        User user = new User();
        user.setRoles(Set.of(UserRole.ADMIN));

        user.setRoles(Set.of(UserRole.USER));

        assertThat(user.getRoles()).containsExactly(UserRole.USER);
    }

    @Test
    void getRoles_isNotBackedByTheInternalSet() {
        User user = new User();
        Set<UserRole> initial = new LinkedHashSet<>(Set.of(UserRole.USER));
        user.setRoles(initial);

        Set<UserRole> view = user.getRoles();

        assertThatThrownBy(() -> view.add(UserRole.ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getPassword_getUsername_isEnabled_delegateToTheirBackingFields() {
        User user = new User();
        user.setEmail("reader@example.com");
        user.setPassword("hashed");
        user.setEnabled(false);

        assertThat(user.getUsername()).isEqualTo("reader@example.com");
        assertThat(user.getPassword()).isEqualTo("hashed");
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void newUser_hasNoNameUntilOneIsSet() {
        User user = new User();

        assertThat(user.getName()).isNull();

        user.setName("Ada Lovelace");

        assertThat(user.getName()).isEqualTo("Ada Lovelace");
    }
}
