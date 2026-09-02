package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.user.dto.ChatPreferenceResponse;
import com.kssasarma.confluencebot.user.dto.ChatPreferenceRequest;
import com.kssasarma.confluencebot.user.dto.UserPreferenceResponse;
import com.kssasarma.confluencebot.user.dto.UserPreferenceUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class PreferenceServiceImpl implements PreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;
    private final ChatPreferenceRepository chatPreferenceRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;

    public PreferenceServiceImpl(UserPreferenceRepository userPreferenceRepository,
                                 ChatPreferenceRepository chatPreferenceRepository,
                                 ChatSessionRepository chatSessionRepository,
                                 UserRepository userRepository) {
        this.userPreferenceRepository = userPreferenceRepository;
        this.chatPreferenceRepository = chatPreferenceRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.userRepository = userRepository;
    }

    @Override
    public UserPreferenceResponse getUserPreferences(User user) {
        return UserPreferenceResponse.from(loadOrCreateUserPreference(user));
    }

    @Override
    public UserPreferenceResponse updateUserPreferences(User user, UserPreferenceUpdateRequest request) {
        UserPreference pref = loadOrCreateUserPreference(user);
        if (request.theme() != null) pref.setTheme(request.theme());
        if (request.language() != null) pref.setLanguage(request.language());
        if (request.responseStyle() != null) pref.setResponseStyle(request.responseStyle());
        if (request.showSources() != null) pref.setShowSources(request.showSources());
        if (request.showConfidence() != null) pref.setShowConfidence(request.showConfidence());
        return UserPreferenceResponse.from(userPreferenceRepository.save(pref));
    }

    @Override
    @Transactional(readOnly = true)
    public ChatPreferenceResponse getChatPreferences(User user, String chatId) {
        // A read never writes: an untouched conversation simply inherits everything.
        return chatPreferenceRepository.findByChatIdAndUserId(chatId, user.getId())
                .map(PreferenceServiceImpl::toResponse)
                .orElseGet(() -> new ChatPreferenceResponse(null, null, null, null));
    }

    @Override
    public ChatPreferenceResponse replaceChatPreferences(User user, String chatId,
                                                         ChatPreferenceRequest request) {
        chatSessionRepository.findByChatIdAndUserId(chatId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + chatId));

        ChatPreference pref = chatPreferenceRepository.findByChatIdAndUserId(chatId, user.getId())
                .orElseGet(() -> {
                    ChatPreference created = new ChatPreference();
                    created.setChatId(chatId);
                    created.setUser(userRepository.getReferenceById(user.getId()));
                    return created;
                });

        pref.setResponseStyle(blankToNull(request.responseStyle()));
        pref.setShowSources(request.showSources());
        pref.setShowConfidence(request.showConfidence());
        pref.setCustomPrompt(blankToNull(request.customPrompt()));

        return toResponse(chatPreferenceRepository.save(pref));
    }

    @Override
    @Transactional(readOnly = true)
    public EffectiveChatPreferences resolve(User user, String chatId) {
        Optional<UserPreference> accountPref = userPreferenceRepository.findByUserId(user.getId());
        Optional<ChatPreference> chatPref = chatId == null
                ? Optional.empty()
                : chatPreferenceRepository.findByChatIdAndUserId(chatId, user.getId());

        String style = chatPref.map(ChatPreference::getResponseStyle)
                .filter(s -> !s.isBlank())
                .or(() -> accountPref.map(UserPreference::getResponseStyle))
                .orElse(null);

        boolean showSources = chatPref.map(ChatPreference::getShowSources)
                .or(() -> accountPref.map(UserPreference::isShowSources))
                .orElse(true);

        boolean showConfidence = chatPref.map(ChatPreference::getShowConfidence)
                .or(() -> accountPref.map(UserPreference::isShowConfidence))
                .orElse(true);

        return new EffectiveChatPreferences(
                ResponseStyle.from(style),
                chatPref.map(ChatPreference::getCustomPrompt).orElse(null),
                showSources,
                showConfidence);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private UserPreference loadOrCreateUserPreference(User user) {
        return userPreferenceRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    UserPreference created = new UserPreference();
                    created.setUser(userRepository.getReferenceById(user.getId()));
                    return userPreferenceRepository.save(created);
                });
    }

    private static ChatPreferenceResponse toResponse(ChatPreference pref) {
        return new ChatPreferenceResponse(
                pref.getResponseStyle(), pref.getShowSources(),
                pref.getShowConfidence(), pref.getCustomPrompt());
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
