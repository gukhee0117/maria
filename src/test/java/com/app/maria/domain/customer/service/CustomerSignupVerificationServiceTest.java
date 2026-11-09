package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.maria.global.config.properties.CiProperties;
import com.app.maria.global.exception.AppException;
import java.time.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class CustomerSignupVerificationServiceTest {
    SignupCodeStore codes = mock(SignupCodeStore.class);
    CustomerSignupEmailSender sender = mock(CustomerSignupEmailSender.class);
    Instant now = Instant.parse("2026-11-08T06:00:00Z");
    CustomerSignupVerificationService service =
            new CustomerSignupVerificationService(
                    codes, sender, Clock.fixed(now, ZoneOffset.UTC), properties());

    private static CiProperties properties() {
        var properties = new CiProperties();
        properties.setPepper("test-signup-pepper");
        return properties;
    }

    @Test
    void recipientAliasesShareLimitsWhileEachBrowserKeepsItsOwnChallenge() {
        when(codes.reserveSend(anyString(), anyString(), anyString())).thenReturn(true);
        var first = service.prepare("browser-a", "User.Name+first@gmail.com", "ip");
        var second = service.prepare("browser-b", "username+second@googlemail.com", "ip");
        var recipients = ArgumentCaptor.forClass(String.class);
        verify(codes, times(2)).reserveSend(anyString(), recipients.capture(), anyString());
        assertThat(recipients.getAllValues().get(0)).isEqualTo(recipients.getAllValues().get(1));
        assertThat(first.pending().identity()).isNotEqualTo(second.pending().identity());
        assertThat(first.pending().email()).isEqualTo("User.Name+first@gmail.com");
    }

    private CustomerSignupVerificationService.Pending send(String email) {
        var prepared = service.prepare("browser", email, "127.0.0.1");
        service.deliver(prepared);
        return prepared.pending();
    }

    @Test
    void preparationDoesNotStartSmtp() {
        when(codes.reserveSend(anyString(), anyString(), anyString())).thenReturn(true);
        var prepared = service.prepare("browser", "user@example.com", "127.0.0.1");
        verifyNoInteractions(sender);
        assertThat(prepared.pending().resendAvailableAt()).isEqualTo(now.plusSeconds(60));
        verify(codes)
                .save(
                        eq(prepared.pending().identity()),
                        eq(prepared.pending().token()),
                        anyString());
    }

    @Test
    void hashesCodeAndVerifiesOneTimeBeforeAcceptingExactAddress() {
        when(codes.reserveSend(anyString(), anyString(), anyString())).thenReturn(true);
        var pending = send("user@example.com");
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("user@example.com"), code.capture());
        assertThat(code.getValue()).matches("[0-9]{6}");
        var stored = ArgumentCaptor.forClass(String.class);
        verify(codes).save(eq(pending.identity()), eq(pending.token()), stored.capture());
        assertThat(stored.getValue()).hasSize(64).doesNotContain(code.getValue());
        assertThat(pending.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(service.describe(pending).maskedEmail()).isEqualTo("u***@example.com");
        assertThatThrownBy(() -> service.requireVerified(pending, pending.email()))
                .isInstanceOf(AppException.class);
        when(codes.consume(eq(pending.identity()), eq(pending.token()), eq(stored.getValue())))
                .thenReturn(true, false);
        var verified = service.verify(pending, code.getValue());
        service.requireVerified(verified, "user@example.com");
        assertThatThrownBy(() -> service.requireVerified(verified, "other@example.com"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.verify(pending, code.getValue()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.verify(verified, code.getValue()))
                .isInstanceOf(AppException.class);
    }

    @Test
    void cooldownPreventsSendAndSave() {
        assertThatThrownBy(() -> send("user@example.com")).isInstanceOf(AppException.class);
        verifyNoInteractions(sender);
    }

    @Test
    void addressCooldownSpansBrowsersAndCaseVariants() {
        assertThatThrownBy(
                        () -> service.prepare("different-browser", "USER@example.com", "127.0.0.1"))
                .isInstanceOf(AppException.class);
        verify(codes)
                .reserveSend(startsWith("browser:"), startsWith("address:"), startsWith("ip:"));
        verifyNoInteractions(sender);
    }

    @Test
    void failedMailCancelsChallenge() {
        when(codes.reserveSend(anyString(), anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("smtp failed")).when(sender).send(any(), any());
        assertThatThrownBy(() -> send("user@example.com"))
                .isInstanceOf(IllegalStateException.class);
        verify(codes).cancel(anyString(), anyString());
    }

    @Test
    void expiredChallengeAndProofAreRejectedWithoutRedis() {
        var pending =
                new CustomerSignupVerificationService.Pending(
                        "user@example.com", "identity", "token", now, now, false);
        var verified =
                new CustomerSignupVerificationService.Pending(
                        "user@example.com", "identity", "token", now, now, true);
        assertThatThrownBy(() -> service.verify(pending, "123456"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.verify(null, "123456")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.requireVerified(verified, verified.email()))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(codes);
    }
}
