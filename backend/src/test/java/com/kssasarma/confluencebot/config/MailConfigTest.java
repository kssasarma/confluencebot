package com.kssasarma.confluencebot.config;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SMTP timeouts that keep an unreachable relay from hanging the caller — {@code
 * createUser} sends the welcome email synchronously, so JavaMail's no-timeout default would
 * otherwise block that request for the OS TCP timeout.
 */
class MailConfigTest {

    @Test
    void javaMailSender_setsConnectionReadAndWriteTimeouts() {
        MailConfig config = new MailConfig();
        ReflectionTestUtils.setField(config, "host", "smtp.example.com");
        ReflectionTestUtils.setField(config, "port", 587);
        ReflectionTestUtils.setField(config, "username", "");
        ReflectionTestUtils.setField(config, "password", "");
        ReflectionTestUtils.setField(config, "smtpAuth", true);
        ReflectionTestUtils.setField(config, "startTlsEnable", true);
        ReflectionTestUtils.setField(config, "startTlsRequired", true);

        JavaMailSenderImpl sender = (JavaMailSenderImpl) config.javaMailSender();

        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout")).isNotBlank();
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.timeout")).isNotBlank();
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout")).isNotBlank();
    }
}
