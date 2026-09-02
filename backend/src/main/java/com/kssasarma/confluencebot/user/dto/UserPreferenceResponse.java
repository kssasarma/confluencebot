package com.kssasarma.confluencebot.user.dto;

import com.kssasarma.confluencebot.user.UserPreference;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Account-wide preferences")
public record UserPreferenceResponse(
        String theme,
        String language,
        String responseStyle,
        boolean showSources,
        boolean showConfidence
) {
    public static UserPreferenceResponse from(UserPreference pref) {
        return new UserPreferenceResponse(
                pref.getTheme(), pref.getLanguage(), pref.getResponseStyle(),
                pref.isShowSources(), pref.isShowConfidence());
    }
}
