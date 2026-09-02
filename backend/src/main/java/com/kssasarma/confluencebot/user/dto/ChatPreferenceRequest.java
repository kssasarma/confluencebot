package com.kssasarma.confluencebot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The complete set of per-conversation overrides.
 *
 * This is a replacement, not a merge: a null field means "inherit the account-wide value", which
 * is the only way a client can take an override back off again.
 */
@Schema(description = "Per-conversation overrides; null means inherit the account-wide value")
public record ChatPreferenceRequest(
        @Pattern(regexp = "concise|balanced|detailed",
                 message = "Response style must be concise, balanced or detailed")
        String responseStyle,

        Boolean showSources,
        Boolean showConfidence,

        @Size(max = 2000, message = "Custom prompt must be at most 2000 characters")
        String customPrompt
) {}
