package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Onboarding and usage analytics — ADMIN only. Question counts are counts, "
        + "never the questions themselves.")
public record AdminAnalyticsResponse(
        OnboardingStats onboarding,
        UsageStats usage
) {

    @Schema(description = "Email/onboarding activity from the admin user event trail")
    public record OnboardingStats(
            long totalUsers,

            @Schema(description = "Users grouped by role name")
            List<RoleCount> usersByRole,

            @Schema(description = "Accounts created in the last 30 days")
            long createdLast30Days,

            @Schema(description = "Temporary passwords re-shared in the last 30 days")
            long resentLast30Days,

            @Schema(description = "Accounts deleted in the last 30 days")
            long deletedLast30Days,

            @Schema(description = "Welcome/re-share emails that failed to send in the last 30 days "
                    + "— mail was down or misconfigured when an admin shared a temporary password")
            long emailDeliveryFailuresLast30Days
    ) {}

    @Schema(description = "How much the chatbot is actually being used — counts only, never the "
            + "questions themselves")
    public record UsageStats(
            long totalQuestions,

            @Schema(description = "Who is asking the most questions, most first")
            List<UserQuestionCount> topUsers,

            @Schema(description = "Question volume by business unit, most first — only users with "
                    + "a business unit set are counted")
            List<BusinessUnitQuestionCount> byBusinessUnit
    ) {}

    public record RoleCount(String role, long count) {}

    public record UserQuestionCount(String email, String name, long questionCount) {}

    public record BusinessUnitQuestionCount(String businessUnit, long questionCount) {}
}
