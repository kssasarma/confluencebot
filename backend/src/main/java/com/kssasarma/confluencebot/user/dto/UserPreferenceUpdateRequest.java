package com.kssasarma.confluencebot.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update of the account-wide preferences. A null field means "leave unchanged" — none of
 * these values is nullable in storage, so there is no ambiguity with "clear this value".
 */
public record UserPreferenceUpdateRequest(
        @Pattern(regexp = "light|dark|system", message = "Theme must be light, dark or system")
        String theme,

        @Size(min = 2, max = 10, message = "Language must be 2–10 characters")
        String language,

        @Pattern(regexp = "concise|balanced|detailed",
                 message = "Response style must be concise, balanced or detailed")
        String responseStyle,

        Boolean showSources,
        Boolean showConfidence
) {}
