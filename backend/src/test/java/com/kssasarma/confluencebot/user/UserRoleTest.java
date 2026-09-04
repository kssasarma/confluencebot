package com.kssasarma.confluencebot.user;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleTest {

    @Test
    void namesOf_multipleRoles_sortsAlphabeticallyRegardlessOfInsertionOrder() {
        assertThat(UserRole.namesOf(Set.of(UserRole.USER, UserRole.ADMIN, UserRole.INGESTOR)))
                .containsExactly("ADMIN", "INGESTOR", "USER");
    }

    @Test
    void namesOf_singleRole_returnsOneName() {
        assertThat(UserRole.namesOf(Set.of(UserRole.ADMIN_READ_ONLY)))
                .containsExactly("ADMIN_READ_ONLY");
    }

    @Test
    void namesOf_emptySet_returnsEmptyList() {
        assertThat(UserRole.namesOf(Set.of())).isEmpty();
    }
}
