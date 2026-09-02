package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.user.dto.ChatMessageResponse;
import com.kssasarma.confluencebot.user.dto.ChatSessionResponse;
import com.kssasarma.confluencebot.user.dto.UpdateChatSessionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class ChatSessionServiceImpl implements ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionServiceImpl.class);

    private static final int TITLE_MAX_LENGTH = 60;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatMessagePayloadCodec payloadCodec;
    private final Duration abandonedSessionTtl;

    public ChatSessionServiceImpl(ChatSessionRepository sessionRepository,
                                  ChatMessageRepository messageRepository,
                                  UserRepository userRepository,
                                  ChatMessagePayloadCodec payloadCodec,
                                  @Value("${chat.session.abandoned-ttl:PT1H}") Duration abandonedSessionTtl) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.payloadCodec = payloadCodec;
        this.abandonedSessionTtl = abandonedSessionTtl;
    }

    @Override
    public List<ChatSessionResponse> listSessions(User user) {
        purgeAbandonedSessions(user);

        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByPinnedDescUpdatedAtDesc(user.getId());
        Map<Long, Long> counts = messageRepository.countsBySessionIds(
                sessions.stream().map(ChatSession::getId).filter(Objects::nonNull).toList());

        return sessions.stream()
                .map(s -> ChatSessionResponse.from(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    @Override
    public ChatSessionResponse createSession(User user, String title) {
        String cleanTitle = normalizeTitle(title);

        if (cleanTitle == null) {
            // Reuse an untouched conversation rather than stacking up another empty one.
            List<ChatSession> untouched = sessionRepository.findUntouchedSessions(user.getId());
            if (!untouched.isEmpty()) {
                ChatSession existing = untouched.get(0);
                existing.touch();
                return ChatSessionResponse.from(existing, 0L);
            }
        }

        ChatSession session = newSession(user, UUID.randomUUID().toString());
        session.setTitle(cleanTitle);
        return ChatSessionResponse.from(sessionRepository.save(session), 0L);
    }

    @Override
    public ChatSessionResponse updateSession(User user, String chatId, UpdateChatSessionRequest request) {
        ChatSession session = requireOwnedSession(user, chatId);
        if (request.title() != null) session.setTitle(normalizeTitle(request.title()));
        if (request.pinned() != null) session.setPinned(request.pinned());
        return ChatSessionResponse.from(session, messageRepository.countBySessionId(session.getId()));
    }

    @Override
    public void deleteSession(User user, String chatId) {
        sessionRepository.deleteByChatIdAndUserId(chatId, user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> transcript(User user, String chatId) {
        ChatSession session = requireOwnedSession(user, chatId);
        return messageRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ChatSessionResponse recordTurn(User user, ChatTurn turn) {
        ChatSession session = getOrCreateSession(user, turn.chatId());

        int nextSequence = messageRepository.findMaxSequenceNo(session.getId()) + 1;
        messageRepository.save(message(session, nextSequence, ChatMessageRole.USER, turn.question(), null, null));
        messageRepository.save(message(session, nextSequence + 1, ChatMessageRole.ASSISTANT, turn.answer(),
                payloadCodec.write(turn.sources()), payloadCodec.write(turn.followUpQuestions())));

        if (session.getTitle() == null) session.setTitle(deriveTitle(turn.question()));
        session.touch();

        return ChatSessionResponse.from(session, messageRepository.countBySessionId(session.getId()));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Resolves the conversation the client is typing into, creating it on first use.
     *
     * The client mints the identifier so that a conversation only reaches the database once it
     * actually carries a message — that is what keeps "New chat" from breeding empty entries.
     */
    private ChatSession getOrCreateSession(User user, String chatId) {
        String id = requireValidChatId(chatId);

        return sessionRepository.findByChatId(id)
                .map(existing -> {
                    if (!existing.getUser().getId().equals(user.getId())) {
                        throw new ResourceNotFoundException("Conversation not found: " + id);
                    }
                    return existing;
                })
                .orElseGet(() -> saveNewSession(user, id));
    }

    private ChatSession saveNewSession(User user, String chatId) {
        try {
            return sessionRepository.saveAndFlush(newSession(user, chatId));
        } catch (DataIntegrityViolationException e) {
            // Two messages raced on the same brand-new conversation; the loser reuses the winner's row.
            log.debug("Conversation {} was created concurrently, reusing it", chatId);
            return sessionRepository.findByChatIdAndUserId(chatId, user.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + chatId));
        }
    }

    private ChatSession newSession(User user, String chatId) {
        ChatSession session = new ChatSession();
        session.setChatId(chatId);
        session.setUser(userRepository.getReferenceById(user.getId()));
        return session;
    }

    private ChatSession requireOwnedSession(User user, String chatId) {
        return sessionRepository.findByChatIdAndUserId(chatId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + chatId));
    }

    private void purgeAbandonedSessions(User user) {
        List<ChatSession> abandoned = sessionRepository.findAbandonedSessions(
                user.getId(), Instant.now().minus(abandonedSessionTtl));
        if (abandoned.isEmpty()) return;
        log.debug("Purging {} abandoned conversation(s) for user {}", abandoned.size(), user.getId());
        sessionRepository.deleteAll(abandoned);
    }

    private ChatMessage message(ChatSession session, int sequenceNo, ChatMessageRole role,
                                String content, String sourcesJson, String followUpsJson) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content);
        message.setSourcesJson(sourcesJson);
        message.setFollowUpsJson(followUpsJson);
        return message;
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole().name(),
                message.getContent(),
                payloadCodec.readSources(message.getSourcesJson()),
                payloadCodec.readStrings(message.getFollowUpsJson()),
                message.getCreatedAt());
    }

    private static String requireValidChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        try {
            return UUID.fromString(chatId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("chatId must be a UUID");
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null) return null;
        String trimmed = title.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** First line of the question, clipped to a sidebar-friendly length. */
    private static String deriveTitle(String question) {
        String firstLine = question.strip().lines().findFirst().orElse(question).strip();
        if (firstLine.length() <= TITLE_MAX_LENGTH) return firstLine;
        return firstLine.substring(0, TITLE_MAX_LENGTH).stripTrailing() + "…";
    }
}
