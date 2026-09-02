package com.kssasarma.confluencebot.user.dto;

import jakarta.validation.constraints.Size;

/** Optional body for conversation creation; a blank title lets the first question name the chat. */
public record CreateChatSessionRequest(
        @Size(max = 200, message = "Title must be at most 200 characters") String title
) {}
