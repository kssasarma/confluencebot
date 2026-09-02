package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ChatPreferenceRepository extends JpaRepository<ChatPreference, Long> {
    Optional<ChatPreference> findByChatId(String chatId);
    Optional<ChatPreference> findByChatIdAndUserId(String chatId, Long userId);
}
