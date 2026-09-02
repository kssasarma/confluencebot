package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;

public interface ChatService {
    ChatApiResponse chat(String query);
}
