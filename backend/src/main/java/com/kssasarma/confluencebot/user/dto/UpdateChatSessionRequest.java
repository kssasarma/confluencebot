package com.kssasarma.confluencebot.user.dto;

import jakarta.validation.constraints.Size;

/** Partial update of a conversation. A null field means "leave unchanged". */
public record UpdateChatSessionRequest(
        @Size(min = 1, max = 200, message = "Title must be 1–200 characters") String title,
        Boolean pinned
) {}
