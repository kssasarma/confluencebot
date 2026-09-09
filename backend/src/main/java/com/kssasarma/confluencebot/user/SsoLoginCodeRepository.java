package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SsoLoginCodeRepository extends JpaRepository<SsoLoginCode, Long> {

    /**
     * Marks a code used, and reports whether this call is the one that did it.
     *
     * <p>Written as a conditional update rather than read-then-write on purpose: two requests
     * racing with the same code both pass a "not consumed yet" read, and only one of them may end
     * up with a session. The database decides that, not the order the two threads happen to run in
     * — the second update matches no row and returns 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SsoLoginCode c SET c.consumed = true
            WHERE c.codeHash = :codeHash AND c.consumed = false AND c.expiresAt > :now
            """)
    int consume(@Param("codeHash") String codeHash, @Param("now") Instant now);

    @Query("SELECT c FROM SsoLoginCode c JOIN FETCH c.user WHERE c.codeHash = :codeHash")
    Optional<SsoLoginCode> findByCodeHashWithUser(@Param("codeHash") String codeHash);

    /** Codes live for a minute; the rows they leave behind should not outlive them by much. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SsoLoginCode c WHERE c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
