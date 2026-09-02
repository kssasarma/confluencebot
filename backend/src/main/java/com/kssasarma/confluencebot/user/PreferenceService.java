package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.user.dto.ChatPreferenceResponse;
import com.kssasarma.confluencebot.user.dto.ChatPreferenceRequest;
import com.kssasarma.confluencebot.user.dto.UserPreferenceResponse;
import com.kssasarma.confluencebot.user.dto.UserPreferenceUpdateRequest;

/** Account-wide preferences and the per-conversation overrides layered on top of them. */
public interface PreferenceService {

    UserPreferenceResponse getUserPreferences(User user);

    UserPreferenceResponse updateUserPreferences(User user, UserPreferenceUpdateRequest request);

    /** Overrides only; a null field means the conversation inherits the account-wide value. */
    ChatPreferenceResponse getChatPreferences(User user, String chatId);

    /** Replaces the overrides wholesale; a null field drops the override. */
    ChatPreferenceResponse replaceChatPreferences(User user, String chatId,
                                                  ChatPreferenceRequest request);

    /** The values the answer pipeline should actually apply. Never returns null. */
    EffectiveChatPreferences resolve(User user, String chatId);
}
