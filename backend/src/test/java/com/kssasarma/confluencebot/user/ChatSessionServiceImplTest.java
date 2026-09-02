package com.kssasarma.confluencebot.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.user.dto.ChatMessageResponse;
import com.kssasarma.confluencebot.user.dto.ChatSessionResponse;
import com.kssasarma.confluencebot.user.dto.UpdateChatSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatSessionServiceImplTest {

    private static final String CHAT_ID = "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c";

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private User user;
    @Mock private User otherUser;

    private ChatSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        when(user.getId()).thenReturn(1L);
        when(otherUser.getId()).thenReturn(2L);
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findMaxSequenceNo(any())).thenReturn(-1);

        service = new ChatSessionServiceImpl(sessionRepository, messageRepository, userRepository,
                new ChatMessagePayloadCodec(new ObjectMapper()), Duration.ofHours(1));
    }

    /** The "New chat" button used to mint a fresh empty conversation on every single click. */
    @Test
    void createSession_reusesAnUntouchedConversationInsteadOfPilingUpEmptyOnes() {
        ChatSession untouched = session(CHAT_ID, null);
        when(sessionRepository.findUntouchedSessions(1L)).thenReturn(List.of(untouched));

        ChatSessionResponse response = service.createSession(user, null);

        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createSession_createsOneWhenTheUserHasNoUntouchedConversation() {
        when(sessionRepository.findUntouchedSessions(1L)).thenReturn(List.of());

        ChatSessionResponse response = service.createSession(user, "  Deployment notes  ");

        assertThat(response.title()).isEqualTo("Deployment notes");
        assertThat(UUID.fromString(response.chatId())).isNotNull();
        assertThat(response.messageCount()).isZero();
    }

    @Test
    void recordTurn_createsTheConversationOnItsFirstQuestionAndNamesItAfterIt() {
        when(sessionRepository.findByChatId(CHAT_ID)).thenReturn(Optional.empty());
        when(messageRepository.countBySessionId(any())).thenReturn(2L);

        ChatSessionResponse response = service.recordTurn(user, new ChatTurn(
                CHAT_ID, "How do I rotate the signing key?", "Run the rotate command.",
                List.of(new SourceReference("1", "Keys", "u", "u", "ENG", 0.9)),
                List.of("What about the old key?")));

        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        assertThat(response.title()).isEqualTo("How do I rotate the signing key?");

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(saved.capture());

        ChatMessage question = saved.getAllValues().get(0);
        ChatMessage answer = saved.getAllValues().get(1);
        assertThat(question.getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(question.getSequenceNo()).isZero();
        assertThat(answer.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(answer.getSequenceNo()).isEqualTo(1);
        assertThat(answer.getSourcesJson()).contains("Keys");
        assertThat(answer.getFollowUpsJson()).contains("What about the old key?");
    }

    @Test
    void recordTurn_keepsTheTitleTheUserChose() {
        ChatSession existing = session(CHAT_ID, "Renamed by hand");
        when(sessionRepository.findByChatId(CHAT_ID)).thenReturn(Optional.of(existing));
        when(messageRepository.findMaxSequenceNo(any())).thenReturn(3);

        ChatSessionResponse response = service.recordTurn(user,
                new ChatTurn(CHAT_ID, "Another question?", "Another answer.", List.of(), List.of()));

        assertThat(response.title()).isEqualTo("Renamed by hand");
    }

    @Test
    void recordTurn_longQuestionsBecomeAClippedTitle() {
        when(sessionRepository.findByChatId(CHAT_ID)).thenReturn(Optional.empty());
        String question = "How do I ".repeat(20);

        ChatSessionResponse response = service.recordTurn(user,
                new ChatTurn(CHAT_ID, question, "An answer.", List.of(), List.of()));

        assertThat(response.title()).hasSizeLessThanOrEqualTo(61).endsWith("…");
    }

    @Test
    void recordTurn_rejectsAConversationIdThatIsNotAUuid() {
        assertThatThrownBy(() -> service.recordTurn(user,
                new ChatTurn("../../etc/passwd", "Question?", "Answer.", List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void recordTurn_refusesToWriteIntoSomebodyElsesConversation() {
        ChatSession theirs = session(CHAT_ID, "Private");
        theirs.setUser(otherUser);
        when(sessionRepository.findByChatId(CHAT_ID)).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.recordTurn(user,
                new ChatTurn(CHAT_ID, "Question?", "Answer.", List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void transcript_readsBackTheStoredSourcesAndFollowUps() {
        ChatSession existing = session(CHAT_ID, "Keys");
        when(sessionRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.of(existing));

        ChatMessage stored = new ChatMessage();
        stored.setSession(existing);
        stored.setRole(ChatMessageRole.ASSISTANT);
        stored.setContent("Run the rotate command.");
        stored.setSourcesJson("[{\"pageId\":\"1\",\"title\":\"Keys\",\"url\":\"u\","
                + "\"anchorUrl\":\"u\",\"spaceKey\":\"ENG\",\"score\":0.9}]");
        stored.setFollowUpsJson("[\"What about the old key?\"]");
        when(messageRepository.findBySessionIdOrderBySequenceNoAsc(any())).thenReturn(List.of(stored));

        List<ChatMessageResponse> transcript = service.transcript(user, CHAT_ID);

        assertThat(transcript).hasSize(1);
        assertThat(transcript.get(0).role()).isEqualTo("ASSISTANT");
        assertThat(transcript.get(0).sources()).extracting(SourceReference::title).containsExactly("Keys");
        assertThat(transcript.get(0).followUpQuestions()).containsExactly("What about the old key?");
    }

    @Test
    void transcript_ofAnUnknownConversationIsNotFound() {
        when(sessionRepository.findByChatIdAndUserId(any(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transcript(user, CHAT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateSession_appliesOnlyThePresentFields() {
        ChatSession existing = session(CHAT_ID, "Original");
        when(sessionRepository.findByChatIdAndUserId(CHAT_ID, 1L)).thenReturn(Optional.of(existing));

        ChatSessionResponse response = service.updateSession(user, CHAT_ID,
                new UpdateChatSessionRequest(null, true));

        assertThat(response.title()).isEqualTo("Original");
        assertThat(response.pinned()).isTrue();
    }

    @Test
    void listSessions_purgesAbandonedDraftsAndReportsMessageCounts() {
        ChatSession abandoned = session("11111111-1111-1111-1111-111111111111", null);
        ChatSession real = session(CHAT_ID, "Keys");
        when(sessionRepository.findAbandonedSessions(eq(1L), any())).thenReturn(List.of(abandoned));
        when(sessionRepository.findByUserIdOrderByPinnedDescUpdatedAtDesc(1L)).thenReturn(List.of(real));
        when(messageRepository.countsBySessionIds(any())).thenReturn(new HashMap<>());

        List<ChatSessionResponse> sessions = service.listSessions(user);

        verify(sessionRepository).deleteAll(List.of(abandoned));
        assertThat(sessions).extracting(ChatSessionResponse::chatId).containsExactly(CHAT_ID);
        assertThat(sessions.get(0).messageCount()).isZero();
    }

    private ChatSession session(String chatId, String title) {
        ChatSession session = new ChatSession();
        session.setChatId(chatId);
        session.setUser(user);
        session.setTitle(title);
        return session;
    }
}
