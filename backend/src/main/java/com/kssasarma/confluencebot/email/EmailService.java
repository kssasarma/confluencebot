package com.kssasarma.confluencebot.email;

public interface EmailService {

    /**
     * Sends a newly onboarded user their sign-in email and temporary password, CC'd to the admin
     * who onboarded them for their own records. Best-effort: mail is optional infrastructure (see
     * {@code MailConfig}), so a misconfigured or unreachable relay returns {@code false} rather
     * than throwing — creating the account must never depend on it.
     *
     * @param ccEmail the onboarding admin's address; skipped if blank or equal to {@code toEmail}
     */
    boolean sendWelcomeEmail(String toEmail, String ccEmail, String tempPassword);
}
