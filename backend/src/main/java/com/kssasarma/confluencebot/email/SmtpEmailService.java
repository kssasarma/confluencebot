package com.kssasarma.confluencebot.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailService implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String appBaseUrl;

    public SmtpEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.from:}") String fromAddress,
            @Value("${app.base-url:}") String appBaseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.appBaseUrl = appBaseUrl;
    }

    @Override
    public boolean sendWelcomeEmail(String toEmail, String tempPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(toEmail);
        message.setSubject("Your Confluence Bot account is ready");
        message.setText(body(toEmail, tempPassword));
        try {
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            logger.warn("Could not send welcome email to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    private String body(String email, String tempPassword) {
        String signInLine = (appBaseUrl == null || appBaseUrl.isBlank())
                ? ""
                : "Sign in here: " + appBaseUrl + "\n\n";
        return """
                An account has been created for you on Confluence Bot.

                Email: %s
                Temporary password: %s

                %sYou'll be asked to choose your own password the first time you sign in. Keep this \
                temporary password safe until then — it will not be shown to you again.
                """.formatted(email, tempPassword, signInLine);
    }
}
