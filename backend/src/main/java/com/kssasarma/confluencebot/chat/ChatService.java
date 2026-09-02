package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;

public interface ChatService {

    /** Answers in one shot. */
    ChatApiResponse chat(ChatQuery query);

    /**
     * Answers incrementally, pushing into {@code listener}.
     *
     * @return a handle that cancels the in-flight answer
     */
    ChatStreamHandle stream(ChatQuery query, ChatStreamListener listener);
}
