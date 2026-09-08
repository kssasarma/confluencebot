package com.kssasarma.confluencebot.email;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock private JavaMailSender mailSender;

    private void stubMimeMessage() {
        lenient().when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    private static String part(MimeMessage message, String mimeType) throws Exception {
        // The real JavaMailSenderImpl calls saveChanges() before transmitting, which is what
        // syncs the Content-Type header used by isMimeType(); our mocked sender never sends for
        // real, so the test has to do it to inspect the built message.
        message.saveChanges();
        String found = findPart(message, mimeType);
        if (found == null) {
            throw new AssertionError("No body part with mime type " + mimeType);
        }
        return found;
    }

    private static String findPart(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = findPart(multipart.getBodyPart(i), mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void sendWelcomeEmail_success_returnsTrueAndIncludesCredentials() throws Exception {
        stubMimeMessage();
        SmtpEmailService service =
                new SmtpEmailService(mailSender, "noreply@example.com", "", "https://bot.example.com");

        boolean result = service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        assertThat(result).isTrue();
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("new@example.com");
        assertThat(sent.getFrom()[0].toString()).isEqualTo("noreply@example.com");
        assertThat(part(sent, "text/plain")).contains("temp-pass-123", "new@example.com", "https://bot.example.com");
        assertThat(part(sent, "text/html")).contains("temp-pass-123", "new@example.com", "https://bot.example.com");
    }

    @Test
    void sendWelcomeEmail_withFromName_setsDisplayNameOnFromAddress() throws Exception {
        stubMimeMessage();
        SmtpEmailService service =
                new SmtpEmailService(mailSender, "noreply@example.com", "Confluence Bot", "");

        service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()[0].toString()).isEqualTo("Confluence Bot <noreply@example.com>");
    }

    @Test
    void sendWelcomeEmail_neverCcsTheOnboardingAdmin() throws Exception {
        stubMimeMessage();
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "", "");

        service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getRecipients(jakarta.mail.Message.RecipientType.CC)).isNull();
    }

    @Test
    void sendWelcomeEmail_namesTheOnboardingAdminInTheBody() throws Exception {
        stubMimeMessage();
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "", "");

        service.sendWelcomeEmail("new@example.com", "Priya Sharma", "temp-pass-123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(part(captor.getValue(), "text/plain")).contains("You were onboarded by Priya Sharma");
        assertThat(part(captor.getValue(), "text/html")).contains("You were onboarded by <strong>Priya Sharma</strong>");
    }

    @Test
    void sendWelcomeEmail_blankOnboardedBy_omitsOnboardedByLine() throws Exception {
        stubMimeMessage();
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "", "");

        service.sendWelcomeEmail("new@example.com", "", "temp-pass-123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(part(captor.getValue(), "text/plain")).doesNotContain("onboarded by");
        assertThat(part(captor.getValue(), "text/html")).doesNotContain("onboarded by");
        assertThat(captor.getValue().getRecipients(jakarta.mail.Message.RecipientType.CC)).isNull();
    }

    @Test
    void sendWelcomeEmail_relayThrows_returnsFalseInsteadOfPropagating() {
        stubMimeMessage();
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "", "");
        doThrow(new MailSendException("relay unreachable")).when(mailSender).send(any(MimeMessage.class));

        boolean result = service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        assertThat(result).isFalse();
    }

    @Test
    void sendWelcomeEmail_noBaseUrlConfigured_omitsSignInLine() throws Exception {
        stubMimeMessage();
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "", "");

        service.sendWelcomeEmail("new@example.com", "admin@example.com", "temp-pass-123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(part(captor.getValue(), "text/plain")).doesNotContain("Sign in here");
        assertThat(part(captor.getValue(), "text/html")).doesNotContain("Sign in to Confluence Bot");
    }

    @Test
    void sendPasswordResetOtp_success_setsFromToAndBody() {
        SmtpEmailService service = new SmtpEmailService(mailSender, "noreply@example.com", "Confluence Bot", "");

        boolean result = service.sendPasswordResetOtp("new@example.com", "123456", 10);

        assertThat(result).isTrue();
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("new@example.com");
        assertThat(sent.getFrom()).isEqualTo("Confluence Bot <noreply@example.com>");
        assertThat(sent.getText()).contains("123456", "10 minutes");
    }
}
