package com.kssasarma.confluencebot.chat.context;

import com.kssasarma.confluencebot.chat.ChatQuery;
import com.kssasarma.confluencebot.config.ChatContextProperties;
import com.kssasarma.confluencebot.user.ChatMessage;
import com.kssasarma.confluencebot.user.ChatMessageRepository;
import com.kssasarma.confluencebot.user.ChatMessageRole;
import com.kssasarma.confluencebot.user.ChatSession;
import com.kssasarma.confluencebot.user.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads recent exchanges back out of the transcript the conversation already writes.
 *
 * <p>No second store and no cache: the answer path persists every turn already, so the history is
 * whatever the transcript says. A cache would only be able to disagree with it — after a delete,
 * after a rename, after the same account answers from two tabs — and a conversation that remembers
 * something the user has deleted is a worse failure than one that costs a query.
 */
@Service
@Transactional(readOnly = true)
public class PersistentConversationHistoryService implements ConversationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PersistentConversationHistoryService.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatContextProperties properties;

    public PersistentConversationHistoryService(ChatSessionRepository sessionRepository,
                                                ChatMessageRepository messageRepository,
                                                ChatContextProperties properties) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    @Override
    public ConversationContext recentContext(ChatQuery query) {
        if (!properties.historyEnabled() || query == null || !query.isPersistable()) {
            return ConversationContext.EMPTY;
        }

        try {
            return load(query);
        } catch (Exception e) {
            // History is an improvement to an answer, never a precondition for one. A conversation
            // that cannot be read is answered without it rather than not answered at all.
            log.warn("Could not read the history of conversation {}: {}", query.chatId(), e.getMessage());
            return ConversationContext.EMPTY;
        }
    }

    private ConversationContext load(ChatQuery query) {
        ChatSession session = sessionRepository
                .findByChatIdAndUserId(query.chatId(), query.user().getId())
                .orElse(null);

        // The first question of a conversation arrives before the conversation exists: the client
        // mints the id and the row is written when the turn is recorded. Nothing to read yet.
        if (session == null) return ConversationContext.EMPTY;

        // Two rows per exchange, and one extra pair of slack so a transcript whose tail starts on
        // an answer — a turn recorded when its question failed to persist — still yields a full
        // window rather than one exchange short.
        int rowLimit = (properties.maxExchanges() + 1) * 2;

        List<ChatMessage> newestFirst =
                messageRepository.findRecentBySessionId(session.getId(), PageRequest.ofSize(rowLimit));

        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);

        List<ConversationExchange> exchanges = pairUp(chronological);
        if (exchanges.size() > properties.maxExchanges()) {
            exchanges = exchanges.subList(exchanges.size() - properties.maxExchanges(), exchanges.size());
        }

        return new ConversationContext(exchanges);
    }

    /**
     * Walks the transcript pairing each question with the answer that followed it.
     *
     * <p>Written as a walk rather than as index arithmetic over alternating rows because the
     * transcript is not guaranteed to alternate. A stream that failed after its question was
     * written, or a window that happens to open on an answer, both produce an unpaired row; a
     * paired walk drops those and keeps going, where index arithmetic would silently shift every
     * later question onto the wrong answer — which is worse than having no history at all.
     */
    private List<ConversationExchange> pairUp(List<ChatMessage> chronological) {
        List<ConversationExchange> exchanges = new ArrayList<>();
        String pendingQuestion = null;

        for (ChatMessage message : chronological) {
            if (message.getRole() == ChatMessageRole.USER) {
                pendingQuestion = message.getContent();
                continue;
            }
            if (pendingQuestion == null) continue;

            ConversationExchange exchange =
                    new ConversationExchange(pendingQuestion, message.getContent())
                            .withAnswerClippedTo(properties.maxAnswerChars());
            pendingQuestion = null;

            if (exchange.isUsable()) exchanges.add(exchange);
        }

        return exchanges;
    }
}
