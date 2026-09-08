package com.kssasarma.confluencebot.email;

import java.io.UnsupportedEncodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Service
public class SmtpEmailService implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final String appBaseUrl;

    public SmtpEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.from:}") String fromAddress,
            @Value("${spring.mail.from-name:}") String fromName,
            @Value("${app.base-url:}") String appBaseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.appBaseUrl = appBaseUrl;
    }

    @Override
    public boolean sendWelcomeEmail(String toEmail, String onboardedBy, String tempPassword) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            setFrom(helper);
            helper.setTo(toEmail);
            helper.setSubject("Your Confluence Bot account is ready");
            helper.setText(welcomeText(toEmail, onboardedBy, tempPassword), welcomeHtml(toEmail, onboardedBy, tempPassword));
            mailSender.send(message);
            return true;
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            logger.warn("Could not send welcome email to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendPasswordResetOtp(String toEmail, String otp, int validMinutes) {
        SimpleMailMessage message = new SimpleMailMessage();
        try {
            String from = formattedFrom();
            if (from != null) {
                message.setFrom(from);
            }
        } catch (UnsupportedEncodingException e) {
            logger.warn("Could not format from address for password reset email to {}: {}", toEmail, e.getMessage());
            return false;
        }
        message.setTo(toEmail);
        message.setSubject("Your Confluence Bot password reset code");
        message.setText("""
                Your password reset code is: %s

                This code expires in %d minutes. If you didn't request a password reset, you can \
                safely ignore this email — your password has not been changed.
                """.formatted(otp, validMinutes));
        try {
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            logger.warn("Could not send password reset code to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    private void setFrom(MimeMessageHelper helper) throws MessagingException, UnsupportedEncodingException {
        if (fromAddress == null || fromAddress.isBlank()) {
            return;
        }
        if (fromName == null || fromName.isBlank()) {
            helper.setFrom(fromAddress);
        } else {
            helper.setFrom(fromAddress, fromName);
        }
    }

    /** Same "Name &lt;address&gt;" formatting as {@link #setFrom}, for the plain {@link SimpleMailMessage}
     * used by the password-reset email, which has no equivalent of {@code MimeMessageHelper#setFrom}. */
    private String formattedFrom() throws UnsupportedEncodingException {
        if (fromAddress == null || fromAddress.isBlank()) {
            return null;
        }
        if (fromName == null || fromName.isBlank()) {
            return fromAddress;
        }
        return new InternetAddress(fromAddress, fromName, "UTF-8").toString();
    }

    private String welcomeText(String email, String onboardedBy, String tempPassword) {
        String signInLine = (appBaseUrl == null || appBaseUrl.isBlank())
                ? ""
                : "Sign in here: " + appBaseUrl + "\n\n";
        String onboardedByLine = (onboardedBy == null || onboardedBy.isBlank())
                ? ""
                : "You were onboarded by " + onboardedBy + ".\n\n";
        return """
                An account has been created for you on Confluence Bot.

                Email: %s
                Temporary password: %s

                %s%sYou'll be asked to choose your own password the first time you sign in. Keep this \
                temporary password safe until then — it will not be shown to you again.
                """.formatted(email, tempPassword, onboardedByLine, signInLine);
    }

    private String welcomeHtml(String email, String onboardedBy, String tempPassword) {
        String onboardedByParagraph = (onboardedBy == null || onboardedBy.isBlank())
                ? ""
                : "You were onboarded by <strong>%s</strong>.".formatted(escapeHtml(onboardedBy));
        String signInButton = (appBaseUrl == null || appBaseUrl.isBlank())
                ? ""
                : """
                <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:28px auto 4px;">
                  <tr>
                    <td align="center" class="cta-cell" style="border-radius:8px;background:#4f46e5;">
                      <a href="%s" class="cta" style="display:inline-block;padding:12px 28px;font-size:15px;\
font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">Sign in to Confluence Bot</a>
                    </td>
                  </tr>
                </table>
                """.formatted(escapeHtml(appBaseUrl));

        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta name="color-scheme" content="light dark">
                <meta name="supported-color-scheme" content="light dark">
                <title>Your Confluence Bot account is ready</title>
                <style>
                  @keyframes pop-in {
                    0%% { opacity: 0; transform: scale(0.6); }
                    70%% { opacity: 1; transform: scale(1.08); }
                    100%% { opacity: 1; transform: scale(1); }
                  }
                  @keyframes fade-up {
                    0%% { opacity: 0; transform: translateY(10px); }
                    100%% { opacity: 1; transform: translateY(0); }
                  }
                  body { margin: 0; padding: 0; background-color: #f1f0fb; }
                  .badge {
                    width: 56px; height: 56px; line-height: 56px; border-radius: 50%%;
                    background: linear-gradient(135deg, #6366f1, #8b5cf6);
                    color: #ffffff; font-size: 28px; text-align: center; margin: 0 auto 16px;
                    animation: pop-in 0.6s ease-out;
                  }
                  .card { animation: fade-up 0.5s ease-out; }
                  .cta, .cta:hover { color: #ffffff !important; }
                  .cta:hover { background: #4338ca !important; }
                  .muted { color: #6b7280; }
                  @media (prefers-color-scheme: dark) {
                    body { background-color: #111827 !important; }
                    .card { background-color: #1f2937 !important; }
                    .heading { color: #f9fafb !important; }
                    .body-text { color: #d1d5db !important; }
                    .muted { color: #9ca3af !important; }
                    .creds { background-color: #111827 !important; border-color: #374151 !important; }
                    .cred-label { color: #d1d5db !important; }
                    .cred-value { color: #f9fafb !important; }
                    .cred-value-strong { color: #a5b4fc !important; }
                    .cred-border { border-top-color: #374151 !important; }
                    .cta-cell { background: #4f46e5 !important; }
                    .cta { background: #4f46e5 !important; }
                  }
                </style>
                </head>
                <body>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f1f0fb;padding:32px 16px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;">
                        <tr>
                          <td align="center" style="padding-bottom:16px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
                            <span style="font-size:14px;letter-spacing:0.08em;color:#6366f1;font-weight:700;text-transform:uppercase;">Confluence Bot</span>
                          </td>
                        </tr>
                        <tr>
                          <td class="card" style="background-color:#ffffff;border-radius:16px;padding:36px 32px;box-shadow:0 8px 24px rgba(79,70,229,0.12);font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
                            <div class="badge">%s</div>
                            <h1 class="heading" style="margin:0 0 8px;font-size:22px;text-align:center;color:#111827;">Your account is ready</h1>
                            <p class="body-text" style="margin:0 0 24px;text-align:center;color:#4b5563;font-size:15px;line-height:1.5;">
                              An account has been created for you on Confluence Bot.
                            </p>
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" class="creds" style="background-color:#f9fafb;border:1px solid #e5e7eb;border-radius:10px;">
                              <tr>
                                <td class="cred-label" style="padding:14px 18px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:14px;color:#4b5563;">Email</td>
                                <td align="right" class="cred-value" style="padding:14px 18px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:14px;color:#111827;">%s</td>
                              </tr>
                              <tr>
                                <td class="cred-label cred-border" style="padding:0 18px 14px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:14px;color:#4b5563;border-top:1px solid #e5e7eb;">Temporary password</td>
                                <td align="right" class="cred-value-strong cred-border" style="padding:14px 18px 14px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:14px;font-weight:600;color:#4f46e5;border-top:1px solid #e5e7eb;">%s</td>
                              </tr>
                            </table>
                            %s
                            <p class="muted" style="text-align:center;font-size:13px;margin:20px 0 0;">%s</p>
                            <p class="body-text" style="margin:24px 0 0;font-size:13px;line-height:1.5;color:#6b7280;text-align:center;">
                              You'll be asked to choose your own password the first time you sign in.
                              Keep this temporary password safe until then — it will not be shown to you again.
                            </p>
                          </td>
                        </tr>
                        <tr>
                          <td align="center" style="padding-top:20px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:12px;color:#9ca3af;">
                            You're receiving this because an admin created a Confluence Bot account for you.
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(
                        "👋",
                        escapeHtml(email),
                        escapeHtml(tempPassword),
                        signInButton,
                        onboardedByParagraph);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
