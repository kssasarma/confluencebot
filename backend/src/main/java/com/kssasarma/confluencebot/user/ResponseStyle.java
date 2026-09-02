package com.kssasarma.confluencebot.user;

import java.util.Locale;

/** How verbose an answer the user wants. Stored as a lower-case string in the preference tables. */
public enum ResponseStyle {

    CONCISE("Answer in at most three sentences. Lead with the answer; omit background."),
    BALANCED("Answer thoroughly but without padding. Use short paragraphs or a list where it helps."),
    DETAILED("Answer in depth: cover prerequisites, the steps themselves, and the caveats worth knowing.");

    private final String instruction;

    ResponseStyle(String instruction) {
        this.instruction = instruction;
    }

    /** The sentence handed to the model to shape answer length. */
    public String instruction() {
        return instruction;
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Never throws: an unknown or missing value falls back to {@link #BALANCED}. */
    public static ResponseStyle from(String value) {
        if (value == null || value.isBlank()) return BALANCED;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BALANCED;
        }
    }
}
