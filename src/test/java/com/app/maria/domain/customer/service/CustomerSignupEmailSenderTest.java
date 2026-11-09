package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.global.exception.AppException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class CustomerSignupEmailSenderTest {
    @SuppressWarnings("unchecked")
    ObjectProvider<JavaMailSender> providers = mock(ObjectProvider.class);

    JavaMailSender sender = mock(JavaMailSender.class);

    @Test
    void sendsFromReturnsWithSignupCodeAndExpiry() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(providers.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);
        new CustomerSignupEmailSender(providers, "sender@example.com")
                .send("customer@example.com", "123456");
        verify(sender).send(message);
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("리턴증권");
        assertThat(message.getSubject()).contains("회원가입 인증번호");
        var output = new java.io.ByteArrayOutputStream();
        message.writeTo(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("password");
    }

    @Test
    void missingSmtpOrSendFailureNeverReportsSuccess() {
        assertThatThrownBy(
                        () ->
                                new CustomerSignupEmailSender(providers, "")
                                        .send("customer@example.com", "123456"))
                .isInstanceOf(AppException.class);
        when(providers.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("recipient and secret must not leak"))
                .when(sender)
                .send(any(MimeMessage.class));
        assertThatThrownBy(
                        () ->
                                new CustomerSignupEmailSender(providers, "sender@example.com")
                                        .send("customer@example.com", "123456"))
                .isInstanceOf(AppException.class)
                .hasCause(null);
    }
}
