package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.maria.global.exception.AppException;
import java.time.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class CustomerSignupVerificationServiceTest {
    SignupCodeStore codes = mock(SignupCodeStore.class);
    CustomerSignupEmailSender sender = mock(CustomerSignupEmailSender.class);
    Instant now = Instant.parse("2026-11-08T06:00:00Z");
    CustomerSignupVerificationService service =
            new CustomerSignupVerificationService(codes, sender, Clock.fixed(now, ZoneOffset.UTC));

    private CustomerSignupVerificationService.Pending send(String email) {
        var prepared = service.prepare("browser", email);
        service.deliver(prepared);
        return prepared.pending();
    }

    @Test
    void preparationDoesNotStartSmtp() {
        when(codes.reserveSend(anyString(), anyString())).thenReturn(true);
        var prepared = service.prepare("browser", "user@example.com");
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
        when(codes.reserveSend(anyString(), anyString())).thenReturn(true);
        var pending = send("user@example.com");
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("user@example.com"), code.capture());
        assertThat(code.getValue()).matches("[0-9]{6}");
        verify(codes)
                .save(
                        pending.identity(),
                        pending.token(),
                        CustomerSignupVerificationService.digest(pending.token(), code.getValue()));
        assertThat(pending.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(service.describe(pending).maskedEmail()).isEqualTo("u***@example.com");
        assertThatThrownBy(() -> service.requireVerified(pending, pending.email()))
                .isInstanceOf(AppException.class);
        when(codes.consume(eq(pending.identity()), eq(pending.token()), anyString()))
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
        assertThatThrownBy(() -> service.prepare("different-browser", "USER@example.com"))
                .isInstanceOf(AppException.class);
        verify(codes)
                .reserveSend(
                        startsWith("browser:"),
                        eq(
                                CustomerSignupVerificationService.digest(
                                        "address", "user@example.com")));
        verifyNoInteractions(sender);
    }

    @Test
    void failedMailCancelsChallenge() {
        when(codes.reserveSend(anyString(), anyString())).thenReturn(true);
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
