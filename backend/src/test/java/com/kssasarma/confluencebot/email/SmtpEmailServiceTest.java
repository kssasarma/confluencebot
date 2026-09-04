package com.kssasarma.confluencebot.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock private JavaMailSender mailSender;

    @Test
    void sendWelcomeEmail_success_returnsTrueAndIncludesCredentials() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "https://bot.example.com");

        boolean result = service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        assertThat(result).isTrue();
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("new@example.com");
        assertThat(sent.getFrom()).isEqualTo("noreply@example.com");
        assertThat(sent.getText()).contains("temp-pass-123", "new@example.com", "https://bot.example.com");
    }

    @Test
    void sendWelcomeEmail_ccsTheOnboardingAdmin() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "");

        service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getCc()).containsExactly("admin@example.com");
    }

    @Test
    void sendWelcomeEmail_adminIsTheNewUser_skipsDuplicateCc() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "");

        service.sendWelcomeEmail("new@example.com", "new@example.com", "temp-pass-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getCc()).isNull();
    }

    @Test
    void sendWelcomeEmail_blankCc_omitsCcHeader() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "");

        service.sendWelcomeEmail("new@example.com", "", "temp-pass-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getCc()).isNull();
    }

    @Test
    void sendWelcomeEmail_relayThrows_returnsFalseInsteadOfPropagating() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "");
        doThrow(new MailSendException("relay unreachable")).when(mailSender).send(any(SimpleMailMessage.class));

        boolean result = service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        assertThat(result).isFalse();
    }

    @Test
    void sendWelcomeEmail_noBaseUrlConfigured_omitsSignInLine() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "");

        service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).doesNotContain("Sign in here");
    }
}
