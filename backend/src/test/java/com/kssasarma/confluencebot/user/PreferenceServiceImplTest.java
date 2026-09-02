package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.user.dto.ChatPreferenceResponse;
import com.kssasarma.confluencebot.user.dto.ChatPreferenceRequest;
import com.kssasarma.confluencebot.user.dto.UserPreferenceResponse;
import com.kssasarma.confluencebot.user.dto.UserPreferenceUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreferenceServiceImplTest {

    private static final String CHAT_ID = "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c";

    @Mock private UserPreferenceRepository userPreferenceRepository;
    @Mock private ChatPreferenceRepository chatPreferenceRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private User user;

    private PreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        when(user.getId()).thenReturn(1L);
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        when(userPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(chatPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service = new PreferenceServiceImpl(userPreferenceRepository, chatPreferenceRepository,
                chatSessionRepository, userRepository);
    }

    @Test
    void getUserPreferences_createsTheDefaultsOnFirstRead() {
        when(userPreferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());

        UserPreferenceResponse response = service.getUserPreferences(user);

        assertThat(response.theme()).isEqualTo("system");
        assertThat(response.responseStyle()).isEqualTo("balanced");
        assertThat(response.showSources()).isTrue();
    }

    @Test
    void updateUserPreferences_leavesOmittedFieldsAlone() {
        UserPreference stored = new UserPreference();
        stored.setTheme("dark");
        stored.setResponseStyle("detailed");
        when(userPreferenceRepository.findByUserId(1L)).thenReturn(Optional.of(stored));

        UserPreferenceResponse response = service.updateUserPreferences(user,
                new UserPreferenceUpdateRequest(null, null, "concise", null, null));

        assertThat(response.theme()).isEqualTo("dark");
        assertThat(response.responseStyle()).isEqualTo("concise");
    }

    /** Reading preferences used to create a row — and to serialize the lazily-loaded user with it. */
    @Test
    void getChatPreferences_doesNotWriteAnythingForAnUntouchedConversation() {
        when(chatPreferenceRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.empty());

        ChatPreferenceResponse response = service.getChatPreferences(user, CHAT_ID);

        assertThat(response.responseStyle()).isNull();
        assertThat(response.customPrompt()).isNull();
        verify(chatPreferenceRepository, never()).save(any());
    }

    @Test
    void replaceChatPreferences_requiresAConversationThatExists() {
        when(chatSessionRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceChatPreferences(user, CHAT_ID,
                new ChatPreferenceRequest("concise", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Picking "Default" in the UI has to be able to take an override back off. */
    @Test
    void replaceChatPreferences_dropsTheOverridesThatAreNoLongerSet() {
        ChatSession session = new ChatSession();
        ChatPreference stored = new ChatPreference();
        stored.setResponseStyle("detailed");
        stored.setCustomPrompt("Answer like a pirate");
        when(chatSessionRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.of(session));
        when(chatPreferenceRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.of(stored));

        ChatPreferenceResponse response = service.replaceChatPreferences(user, CHAT_ID,
                new ChatPreferenceRequest(null, true, null, "   "));

        assertThat(response.responseStyle()).isNull();
        assertThat(response.customPrompt()).isNull();
        assertThat(response.showSources()).isTrue();
        assertThat(response.showConfidence()).isNull();
    }

    @Test
    void resolve_prefersTheConversationOverrideOverTheAccountDefault() {
        UserPreference account = new UserPreference();
        account.setResponseStyle("detailed");
        account.setShowSources(false);
        ChatPreference chat = new ChatPreference();
        chat.setResponseStyle("concise");
        chat.setCustomPrompt("Assume I am on Windows");
        when(userPreferenceRepository.findByUserId(1L)).thenReturn(Optional.of(account));
        when(chatPreferenceRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.of(chat));

        EffectiveChatPreferences resolved = service.resolve(user, CHAT_ID);

        assertThat(resolved.responseStyle()).isEqualTo(ResponseStyle.CONCISE);
        assertThat(resolved.customPrompt()).isEqualTo("Assume I am on Windows");
        assertThat(resolved.showSources()).isFalse();
    }

    @Test
    void resolve_fallsBackToTheAccountDefaultAndThenToTheBuiltInOne() {
        when(userPreferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(chatPreferenceRepository.findByChatIdAndUserId(any(), anyLong())).thenReturn(Optional.empty());

        EffectiveChatPreferences resolved = service.resolve(user, null);

        assertThat(resolved.responseStyle()).isEqualTo(ResponseStyle.BALANCED);
        assertThat(resolved.showSources()).isTrue();
        assertThat(resolved.hasCustomPrompt()).isFalse();
    }

    @Test
    void resolve_ignoresAnUnknownStoredStyleInsteadOfBlowingUp() {
        UserPreference account = new UserPreference();
        account.setResponseStyle("shakespearean");
        when(userPreferenceRepository.findByUserId(1L)).thenReturn(Optional.of(account));

        assertThat(service.resolve(user, null).responseStyle()).isEqualTo(ResponseStyle.BALANCED);
    }
}
