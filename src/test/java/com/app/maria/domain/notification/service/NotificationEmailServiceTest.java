package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.app.maria.domain.notification.event.CustomerNotificationCreated;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class NotificationEmailServiceTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider() {
        return mock(ObjectProvider.class);
    }

    private CustomerNotificationCreated event(String email) {
        return new CustomerNotificationCreated(
                1L,
                "정산이 완료됐어요",
                "내용",
                true,
                email,
                new com.app.maria.domain.notification.dto.SettlementEmailDetails(
                        java.math.BigDecimal.ONE, java.time.LocalDateTime.of(2026, 11, 8, 12, 0)));
    }

    private NotificationEmailRenderer renderer() {
        var renderer = mock(NotificationEmailRenderer.class);
        when(renderer.render(any()))
                .thenReturn(
                        new NotificationEmailRenderer.Rendered(
                                "일반 본문",
                                "<html><body>한글 본문<img src=\"cid:returns-logo\"></body></html>"));
        return renderer;
    }

    private MimeMessage message() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private NotificationEmailVerificationService verification() {
        var guard = mock(NotificationEmailVerificationService.class);
        when(guard.isVerified(anyLong(), anyString())).thenReturn(true);
        return guard;
    }

    @Test
    void smtpReceivesUtf8HtmlPlainAlternativeAndEmbeddedLogo() throws Exception {
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message());
        new NotificationEmailService(provider, "from@example.com", renderer(), verification())
                .deliverSettlement(event("to@example.com"));
        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        MimeMessage message = captured.getValue();
        message.saveChanges();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("to@example.com");
        assertThat(message.getSubject()).isEqualTo("[Returns] 정산이 완료됐어요");
        assertThat(message.getContentType()).contains("multipart/");
        var output = new java.io.ByteArrayOutputStream();
        message.writeTo(output);
        MimeMessage received =
                new MimeMessage(
                        Session.getInstance(new Properties()),
                        new java.io.ByteArrayInputStream(output.toByteArray()));
        InternetAddress senderAddress = (InternetAddress) received.getFrom()[0];
        assertThat(senderAddress.getPersonal()).isEqualTo("리턴증권");
        assertThat(senderAddress.getAddress()).isEqualTo("from@example.com");
        String wire = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(wire)
                .contains(
                        "text/plain",
                        "text/html",
                        "charset=UTF-8",
                        "Content-ID: <returns-logo>",
                        "image/png");
    }

    @Test
    void missingAddressAndUnconfiguredSmtpNeverPretendToSend() {
        var provider = provider();
        var renderer = mock(NotificationEmailRenderer.class);
        var service = new NotificationEmailService(provider, "", renderer, verification());
        service.deliverSettlement(event(null));
        verifyNoInteractions(provider, renderer);
        service.deliverSettlement(event("to@example.com"));
        verify(provider).getIfAvailable();
        verifyNoInteractions(renderer);
    }

    @Test
    void smtpFailureAndAuthenticationFailureAreAttemptedOnlyOnce() {
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message());
        doThrow(new MailSendException("temporary"))
                .doNothing()
                .when(sender)
                .send(any(MimeMessage.class));
        var service =
                new NotificationEmailService(
                        provider, "from@example.com", renderer(), verification());
        service.deliverSettlement(event("to@example.com"));
        verify(sender, times(1)).send(any(MimeMessage.class));
        reset(sender);
        when(sender.createMimeMessage()).thenReturn(message());
        doThrow(new MailAuthenticationException("invalid"))
                .when(sender)
                .send(any(MimeMessage.class));
        service.deliverSettlement(event("to@example.com"));
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void unverifiedRecipientNeverReachesSmtp() {
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        var guard = mock(NotificationEmailVerificationService.class);
        var renderer = mock(NotificationEmailRenderer.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        new NotificationEmailService(provider, "from@example.com", renderer, guard)
                .deliverSettlement(event("to@example.com"));
        verifyNoInteractions(sender, renderer);
    }

    @Test
    void renderingFailureDoesNotSendAnIncompleteMessage() {
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        var renderer = mock(NotificationEmailRenderer.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(renderer.render(any())).thenThrow(new IllegalStateException("template"));
        new NotificationEmailService(provider, "from@example.com", renderer, verification())
                .deliverSettlement(event("to@example.com"));
        verify(sender, never()).send(any(MimeMessage.class));
    }
}
