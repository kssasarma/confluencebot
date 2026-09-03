package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /**
     * Used by the OTDS sign-in path, where the address is whatever the directory happens to emit.
     *
     * <p>A directory that returns {@code Jane.Doe@corp.example} for an account onboarded here as
     * {@code jane.doe@corp.example} is describing the same person, and matching case-sensitively
     * would answer that by creating a second account — then failing on the unique index the moment
     * the casing agreed.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /** The stable link to an OTDS identity: the subject claim, not the address. */
    Optional<User> findByExternalId(String externalId);

    boolean existsByEmail(String email);
}
