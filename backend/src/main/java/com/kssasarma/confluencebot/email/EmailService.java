package com.kssasarma.confluencebot.email;

public interface EmailService {

    /**
     * Sends a newly onboarded user their sign-in email and temporary password. The onboarding
     * admin is named in the body ("You were onboarded by ...") rather than CC'd — the admin
     * already sees the outcome in the Settings UI, and CC'ing them on every user's credentials
     * put a temporary password in a second inbox for no reason. Best-effort: mail is optional
     * infrastructure (see {@code MailConfig}), so a misconfigured or unreachable relay returns
     * {@code false} rather than throwing — creating the account must never depend on it.
     *
     * @param onboardedBy the onboarding admin's display name or email; omitted from the body if
     *                    blank
     */
    boolean sendWelcomeEmail(String toEmail, String onboardedBy, String tempPassword);
}
