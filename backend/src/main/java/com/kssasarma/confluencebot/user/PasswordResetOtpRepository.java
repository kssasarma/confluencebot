package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {

    Optional<PasswordResetOtp> findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(Long userId);

    /**
     * Requesting a new code retires whatever was issued before it — only the most recent code for
     * a user is ever valid, so a reader who asks twice is not left guessing which email is current.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetOtp o SET o.consumed = true WHERE o.user.id = :userId AND o.consumed = false")
    void consumeAllByUserId(Long userId);
}
