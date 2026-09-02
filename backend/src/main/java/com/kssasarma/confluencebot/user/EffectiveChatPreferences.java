package com.kssasarma.confluencebot.user;

/**
 * The preferences that actually apply to one conversation: per-chat overrides resolved against
 * the account-wide defaults.
 */
public record EffectiveChatPreferences(
        ResponseStyle responseStyle,
        String customPrompt,
        boolean showSources,
        boolean showConfidence
) {
    public static EffectiveChatPreferences defaults() {
        return new EffectiveChatPreferences(ResponseStyle.BALANCED, null, true, true);
    }

    public boolean hasCustomPrompt() {
        return customPrompt != null && !customPrompt.isBlank();
    }
}
