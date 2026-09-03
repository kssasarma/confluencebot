package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.user.dto.ChatMessageMatch;
import com.kssasarma.confluencebot.user.dto.ChatMessageResponse;
import com.kssasarma.confluencebot.user.dto.ChatSessionPage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class ChatSessionServiceImpl implements ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionServiceImpl.class);

    private static final int TITLE_MAX_LENGTH = 60;

    /** Page sizes a client may ask for. A generous ceiling, but a ceiling. */
    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 100;

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

    // ── Listing and search ────────────────────────────────────────────────────

    @Override
    public ChatSessionPage listSessions(User user, String query, String cursor, int limit) {
        purgeAbandonedSessions(user);

        int pageSize = clampPageSize(limit);
        ChatSessionCursor from = ChatSessionCursor.decode(cursor).orElse(null);
        String search = normalizeQuery(query);

        // One row beyond the page is fetched purely to learn whether another page exists, which
        // avoids a second COUNT query whose answer would be stale by the time it was returned.
        int fetchSize = pageSize + 1;

        return search == null
                ? page(listPage(user, from, fetchSize), pageSize)
                : page(searchPage(user, search, from, fetchSize), pageSize);
    }

    private List<Row> listPage(User user, ChatSessionCursor from, int fetchSize) {
        List<ChatSession> sessions = sessionRepository.findPage(
                user.getId(), pinnedOf(from), updatedAtOf(from), idOf(from), fetchSize);

        Map<Long, Long> counts = countsFor(sessions.stream().map(ChatSession::getId).toList());

        List<Row> rows = new ArrayList<>(sessions.size());
        for (ChatSession session : sessions) {
            rows.add(new Row(
                    ChatSessionResponse.from(session, counts.getOrDefault(session.getId(), 0L)),
                    ChatSessionCursor.of(session).orElse(null)));
        }
        return rows;
    }

    private List<Row> searchPage(User user, String search, ChatSessionCursor from, int fetchSize) {
        List<ChatSessionSearchRow> hits = sessionRepository.search(
                user.getId(), search, likePattern(search),
                pinnedOf(from), updatedAtOf(from), idOf(from), fetchSize);

        Map<Long, Long> counts = countsFor(hits.stream().map(ChatSessionSearchRow::getId).toList());

        List<Row> rows = new ArrayList<>(hits.size());
        for (ChatSessionSearchRow hit : hits) {
            boolean pinned = Boolean.TRUE.equals(hit.getPinned());
            ChatMessageMatch match = hit.getSnippet() == null
                    ? ChatMessageMatch.titleOnly()
                    : new ChatMessageMatch(hit.getMatchMessageId(), hit.getSnippet());

            rows.add(new Row(
                    new ChatSessionResponse(hit.getChatId(), hit.getTitle(), pinned,
                            counts.getOrDefault(hit.getId(), 0L),
                            hit.getCreatedAt(), hit.getUpdatedAt(), false, match),
                    new ChatSessionCursor(pinned, hit.getUpdatedAt(), hit.getId())));
        }
        return rows;
    }

    /** Trims the look-ahead row and turns the last kept row into the next page's bookmark. */
    private static ChatSessionPage page(List<Row> rows, int pageSize) {
        boolean hasMore = rows.size() > pageSize;
        List<Row> kept = hasMore ? rows.subList(0, pageSize) : rows;

        List<ChatSessionResponse> items = kept.stream().map(Row::response).toList();

        ChatSessionCursor last = kept.isEmpty() ? null : kept.get(kept.size() - 1).cursor();
        String nextCursor = hasMore && last != null ? last.encode() : null;

        return new ChatSessionPage(items, nextCursor);
    }

    private Map<Long, Long> countsFor(List<Long> sessionIds) {
        return messageRepository.countsBySessionIds(
                sessionIds.stream().filter(Objects::nonNull).toList());
    }

    /**
     * A page row: what the client sees, and where to resume after it.
     *
     * The cursor is null only for a row with no surrogate key, which cannot occur for a persisted
     * conversation; the page then reports no further pages rather than failing the request.
     */
    private record Row(ChatSessionResponse response, ChatSessionCursor cursor) {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        messageRepository.save(userMessage(session, nextSequence, turn.question()));
        messageRepository.save(assistantMessage(session, nextSequence + 1, turn));

        if (session.getTitle() == null) session.setGeneratedTitle(deriveTitle(turn.question()));
        session.touch();

        return ChatSessionResponse.from(session, messageRepository.countBySessionId(session.getId()));
    }

    @Override
    public boolean applyGeneratedTitle(User user, String chatId, String title) {
        String cleanTitle = normalizeTitle(title);
        if (cleanTitle == null) return false;

        ChatSession session = sessionRepository.findByChatIdAndUserId(chatId, user.getId()).orElse(null);
        if (session == null) {
            log.debug("Conversation {} vanished before its title could be refined", chatId);
            return false;
        }
        if (!session.isTitleGenerated()) {
            log.debug("Conversation {} was renamed by its owner; keeping their title", chatId);
            return false;
        }

        session.setGeneratedTitle(clip(cleanTitle));
        return true;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Resolves the conversation the client is typing into, creating it on first use.
     *
     * <p>The client mints the identifier so that a conversation only reaches the database once it
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

    private ChatMessage userMessage(ChatSession session, int sequenceNo, String content) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setSequenceNo(sequenceNo);
        message.setRole(ChatMessageRole.USER);
        message.setContent(content);
        return message;
    }

    private ChatMessage assistantMessage(ChatSession session, int sequenceNo, ChatTurn turn) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setSequenceNo(sequenceNo);
        message.setRole(ChatMessageRole.ASSISTANT);
        message.setContent(turn.answer());
        message.setSourcesJson(payloadCodec.write(turn.sources()));
        message.setFollowUpsJson(payloadCodec.write(turn.followUpQuestions()));
        message.setCitationsJson(payloadCodec.write(turn.citations()));
        message.setConfidence(turn.confidence());
        return message;
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole().name(),
                message.getContent(),
                payloadCodec.readSources(message.getSourcesJson()),
                payloadCodec.readStrings(message.getFollowUpsJson()),
                payloadCodec.readCitations(message.getCitationsJson()),
                message.getConfidence(),
                message.getCreatedAt());
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static Boolean pinnedOf(ChatSessionCursor cursor) {
        return cursor == null ? null : cursor.pinned();
    }

    private static Instant updatedAtOf(ChatSessionCursor cursor) {
        return cursor == null ? null : cursor.updatedAt();
    }

    private static Long idOf(ChatSessionCursor cursor) {
        return cursor == null ? null : cursor.id();
    }

    private static int clampPageSize(int limit) {
        if (limit <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private static String normalizeQuery(String query) {
        if (query == null) return null;
        String trimmed = query.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Escapes the wildcards so a user searching for a literal {@code %} does not match everything.
     * Paired with {@code ESCAPE '\'} in the query.
     */
    private static String likePattern(String query) {
        String escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
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
        return clip(question.strip().lines().findFirst().orElse(question).strip());
    }

    private static String clip(String text) {
        if (text.length() <= TITLE_MAX_LENGTH) return text;
        return text.substring(0, TITLE_MAX_LENGTH).stripTrailing() + "…";
    }
}
