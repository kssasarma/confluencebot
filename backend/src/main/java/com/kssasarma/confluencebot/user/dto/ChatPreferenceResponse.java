package com.kssasarma.confluencebot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Per-conversation overrides. Null means \"inherit the account-wide value\".")
public record ChatPreferenceResponse(
        String responseStyle,
        Boolean showSources,
        Boolean showConfidence,
        String customPrompt
) {}
