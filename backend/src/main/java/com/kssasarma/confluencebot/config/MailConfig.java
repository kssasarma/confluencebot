package com.kssasarma.confluencebot.config;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Replaces Spring Boot's autoconfigured JavaMailSenderImpl. That default always calls
 * {@code Transport.connect(host, port, username, password)} whenever a username is configured —
 * and JavaMail's SMTPTransport treats a non-null username *and* non-null password (an unset
 * MAIL_PASSWORD still resolves to "", not null) as a signal to attempt AUTH, regardless of the
 * mail.smtp.auth property. That breaks relays that don't support AUTH but still have
 * MAIL_USERNAME configured (e.g. for sender identification). Only hand credentials to the sender
 * when auth is actually enabled, so mail.smtp.auth=false is respected as intended.
 */
@Configuration
public class MailConfig {

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:true}")
    private boolean smtpAuth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}")
    private boolean startTlsEnable;

    @Value("${spring.mail.properties.mail.smtp.starttls.required:true}")
    private boolean startTlsRequired;

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);

        if (smtpAuth && username != null && !username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(password);
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(smtpAuth));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTlsEnable));
        props.put("mail.smtp.starttls.required", String.valueOf(startTlsRequired));

        return sender;
    }
}
