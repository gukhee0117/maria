package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.response.CustomerSignupChallengeDTO;
import com.app.maria.global.config.properties.CiProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerSignupVerificationService {
    private final SignupCodeStore codes;
    private final CustomerSignupEmailSender sender;
    private final Clock clock;
    private final CiProperties ciProperties;
    private final SecureRandom random = new SecureRandom();

    public record Pending(
            String email,
            String identity,
            String token,
            Instant expiresAt,
            Instant resendAvailableAt,
            boolean verified)
            implements Serializable {}

    /** The plaintext code exists only in this request; it is never stored in the session. */
    public record Prepared(Pending pending, String code) {}

    // Email format is validated by @Valid on CustomerSignupEmailRequestDTO at the API boundary.
    public Prepared prepare(String browserId, String email, String remoteAddress) {
        String identity =
                "challenge:"
                        + fingerprint(
                                "challenge", browserId + ":" + email.toLowerCase(Locale.ROOT));
        if (!codes.reserveSend(
                "browser:" + fingerprint("browser", browserId),
                "address:" + fingerprint("address", canonicalRecipient(email)),
                "ip:" + fingerprint("ip", remoteAddress)))
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_CODE_RATE_LIMITED);
        String token = UUID.randomUUID().toString();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant now =
                clock.instant(); // Security expiry uses real time, independent of SYSTEM_CLOCK.
        Pending pending =
                new Pending(
                        email,
                        identity,
                        token,
                        now.plusSeconds(SignupVerificationPolicy.CODE_TTL_SECONDS),
                        now.plusSeconds(SignupVerificationPolicy.RESEND_COOLDOWN_SECONDS),
                        false);
        codes.save(identity, token, fingerprint("code", token + ":" + code));
        return new Prepared(pending, code);
    }

    /** SMTP is deliberately separate from prepare so callers do not hold a session lock for I/O. */
    public void deliver(Prepared prepared) {
        Pending pending = prepared.pending();
        try {
            sender.send(pending.email(), prepared.code());
        } catch (RuntimeException failure) {
            cancel(pending);
            throw failure;
        }
    }

    public void cancel(Pending pending) {
        codes.cancel(pending.identity(), pending.token());
    }

    public Pending verify(Pending pending, String code) {
        Instant now = clock.instant();
        if (pending == null
                || pending.verified()
                || !now.isBefore(pending.expiresAt())
                || !codes.consume(
                        pending.identity(),
                        pending.token(),
                        fingerprint("code", pending.token() + ":" + code)))
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_CODE_INVALID);
        return new Pending(
                pending.email(),
                pending.identity(),
                pending.token(),
                now.plusSeconds(SignupVerificationPolicy.VERIFIED_TTL_SECONDS),
                pending.resendAvailableAt(),
                true);
    }

    public void requireVerified(Pending pending, String email) {
        if (pending == null
                || !pending.verified()
                || !pending.email().equals(email)
                || !clock.instant().isBefore(pending.expiresAt()))
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_CODE_INVALID);
    }

    public CustomerSignupChallengeDTO describe(Pending pending) {
        String email = pending.email();
        int at = email.lastIndexOf('@');
        String masked = at > 0 ? email.substring(0, 1) + "***" + email.substring(at) : "***";
        return new CustomerSignupChallengeDTO(
                masked, pending.expiresAt(), pending.resendAvailableAt());
    }

    // Canonicalization is used only for rate limiting, never for delivery or ownership checks.
    // Requires an email already validated by @Valid on CustomerSignupEmailRequestDTO.
    static String canonicalRecipient(String email) {
        String lower = email.toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf('@');
        String local = lower.substring(0, at), domain = lower.substring(at + 1);
        int plus = local.indexOf('+');
        if (plus >= 0) local = local.substring(0, plus);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }

    private String fingerprint(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            ciProperties.getPepper().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    ("signup:" + purpose + ":" + value)
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("가입 인증 해시 생성 실패", failure);
        }
    }
}
