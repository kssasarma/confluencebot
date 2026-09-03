package com.kssasarma.confluencebot.chat.context;

import com.kssasarma.confluencebot.chat.ChatQuery;
import com.kssasarma.confluencebot.config.ChatContextProperties;
import com.kssasarma.confluencebot.user.ChatMessage;
import com.kssasarma.confluencebot.user.ChatMessageRepository;
import com.kssasarma.confluencebot.user.ChatMessageRole;
import com.kssasarma.confluencebot.user.ChatSession;
import com.kssasarma.confluencebot.user.ChatSessionRepository;
import com.kssasarma.confluencebot.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistentConversationHistoryServiceTest {

    private static final String CHAT_ID = "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c";
    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 42L;

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private User user;

    private PersistentConversationHistoryService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(properties(true, 3, 1200));
        when(user.getId()).thenReturn(USER_ID);
    }

    private PersistentConversationHistoryService serviceWith(ChatContextProperties properties) {
        return new PersistentConversationHistoryService(
                sessionRepository, messageRepository, properties);
    }

    private static ChatContextProperties properties(boolean enabled, int maxExchanges, int maxAnswerChars) {
        return new ChatContextProperties(enabled, maxExchanges, maxAnswerChars, true,
                Duration.ofSeconds(3), 400);
    }

    @Test
    void recentExchanges_areReturnedOldestFirst() {
        givenSession();
        givenTranscript(
                message(0, ChatMessageRole.USER, "First question"),
                message(1, ChatMessageRole.ASSISTANT, "First answer"),
                message(2, ChatMessageRole.USER, "Second question"),
                message(3, ChatMessageRole.ASSISTANT, "Second answer"));

        ConversationContext context = service.recentContext(query());

        assertThat(context.exchanges()).containsExactly(
                new ConversationExchange("First question", "First answer"),
                new ConversationExchange("Second question", "Second answer"));
    }

    @Test
    void onlyTheConfiguredNumberOfExchanges_isCarriedForward() {
        givenSession();
        List<ChatMessage> transcript = new ArrayList<>();
        for (int turn = 0; turn < 5; turn++) {
            transcript.add(message(turn * 2, ChatMessageRole.USER, "Question " + turn));
            transcript.add(message(turn * 2 + 1, ChatMessageRole.ASSISTANT, "Answer " + turn));
        }
        givenTranscript(transcript.toArray(ChatMessage[]::new));

        ConversationContext context = service.recentContext(query());

        assertThat(context.exchanges()).extracting(ConversationExchange::question)
                .containsExactly("Question 2", "Question 3", "Question 4");
    }

    @Test
    void theTranscriptIsReadWithALimit_soALongConversationCostsNoMoreThanAShortOne() {
        givenSession();
        givenTranscript();

        service.recentContext(query());

        ArgumentCaptor<Pageable> limit = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findRecentBySessionId(eq(SESSION_ID), limit.capture());
        assertThat(limit.getValue().getPageSize()).isEqualTo((3 + 1) * 2);
    }

    @Test
    void aLongAnswer_isClippedOnAWordBoundary() {
        givenSession();
        givenTranscript(
                message(0, ChatMessageRole.USER, "A question"),
                message(1, ChatMessageRole.ASSISTANT,
                        "The collector reads its configuration from the environment at startup"));

        ConversationContext context = serviceWith(properties(true, 3, 20)).recentContext(query());

        String answer = context.exchanges().get(0).answer();
        assertThat(answer).endsWith("…").doesNotContain("startup");
        // Clipped between words, never mid-word.
        assertThat(answer.replace(" …", "")).isEqualTo("The collector reads");
    }

    @Test
    void anUnpairedQuestion_isDroppedRatherThanShiftingEveryLaterAnswer() {
        givenSession();
        givenTranscript(
                // A turn whose answer never landed — a stream that died after the question was written.
                message(0, ChatMessageRole.USER, "Orphaned question"),
                message(1, ChatMessageRole.USER, "Real question"),
                message(2, ChatMessageRole.ASSISTANT, "Real answer"));

        ConversationContext context = service.recentContext(query());

        assertThat(context.exchanges())
                .containsExactly(new ConversationExchange("Real question", "Real answer"));
    }

    @Test
    void aWindowOpeningOnAnAnswer_dropsIt() {
        givenSession();
        givenTranscript(
                message(9, ChatMessageRole.ASSISTANT, "The tail of an earlier answer"),
                message(10, ChatMessageRole.USER, "A question"),
                message(11, ChatMessageRole.ASSISTANT, "An answer"));

        ConversationContext context = service.recentContext(query());

        assertThat(context.exchanges())
                .containsExactly(new ConversationExchange("A question", "An answer"));
    }

    @Test
    void anAnonymousQuestion_hasNoConversationToRead() {
        ConversationContext context = service.recentContext(ChatQuery.of("How do I deploy?"));

        assertThat(context.isEmpty()).isTrue();
        verifyNoInteractions(sessionRepository, messageRepository);
    }

    @Test
    void theFirstQuestionOfAConversation_arrivesBeforeTheConversationExists() {
        when(sessionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThat(service.recentContext(query()).isEmpty()).isTrue();
        verifyNoInteractions(messageRepository);
    }

    @Test
    void whenTurnedOff_nothingIsRead() {
        PersistentConversationHistoryService disabled = serviceWith(properties(false, 3, 1200));

        assertThat(disabled.recentContext(query()).isEmpty()).isTrue();
        verifyNoInteractions(sessionRepository, messageRepository);
    }

    @Test
    void aTranscriptThatCannotBeRead_answersWithoutHistoryRatherThanFailing() {
        givenSession();
        when(messageRepository.findRecentBySessionId(anyLong(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timed out"));

        assertThat(service.recentContext(query()).isEmpty()).isTrue();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private ChatQuery query() {
        return new ChatQuery("And in staging?", CHAT_ID, user);
    }

    private void givenSession() {
        ChatSession session = new ChatSession();
        session.setChatId(CHAT_ID);
        setId(session);
        when(sessionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(session));
    }

    /** The repository returns newest first; the service is what puts it back in order. */
    private void givenTranscript(ChatMessage... chronological) {
        List<ChatMessage> newestFirst = new ArrayList<>(List.of(chronological));
        java.util.Collections.reverse(newestFirst);
        when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(newestFirst);
    }

    private static ChatMessage message(int sequenceNo, ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static void setId(ChatSession session) {
        try {
            java.lang.reflect.Field id = ChatSession.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(session, SESSION_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
