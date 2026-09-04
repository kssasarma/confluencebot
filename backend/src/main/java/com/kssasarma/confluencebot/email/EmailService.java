package com.kssasarma.confluencebot.email;

public interface EmailService {

    /**
     * Sends a newly onboarded user their sign-in email and temporary password. Best-effort: mail
     * is optional infrastructure (see {@code MailConfig}), so a misconfigured or unreachable relay
     * returns {@code false} rather than throwing — creating the account must never depend on it.
     */
    boolean sendWelcomeEmail(String toEmail, String tempPassword);
}
