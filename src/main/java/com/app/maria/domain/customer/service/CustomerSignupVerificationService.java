package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.response.CustomerSignupChallengeDTO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerSignupVerificationService {
    private final SignupCodeStore codes;
    private final CustomerSignupEmailSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record Pending(
            String email,
            String identity,
            String token,
            Instant expiresAt,
            Instant resendAvailableAt,
            boolean verified)
            implements Serializable {}

    public Pending send(String browserId, String email) {
        if (email == null || email.isBlank())
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_EMAIL_REQUIRED);
        // Address cooldown spans browsers; browser cooldown prevents rotating recipient addresses.
        String identity = digest("address", email.toLowerCase(Locale.ROOT));
        if (!codes.reserveSend("browser:" + digest("browser", browserId))
                || !codes.reserveSend(identity))
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_CODE_RATE_LIMITED);
        String token = UUID.randomUUID().toString();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant now =
                clock.instant(); // Security expiry uses real time, independent of SYSTEM_CLOCK.
        Pending pending =
                new Pending(
                        email, identity, token, now.plusSeconds(300), now.plusSeconds(60), false);
        codes.save(identity, token, digest(token, code));
        try {
            sender.send(email, code);
        } catch (RuntimeException failure) {
            codes.cancel(identity, token);
            throw failure;
        }
        return pending;
    }

    public Pending verify(Pending pending, String code) {
        if (pending == null
                || pending.verified()
                || !clock.instant().isBefore(pending.expiresAt())
                || !codes.consume(
                        pending.identity(), pending.token(), digest(pending.token(), code)))
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_CODE_INVALID);
        return new Pending(
                pending.email(),
                pending.identity(),
                pending.token(),
                clock.instant().plusSeconds(600),
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

    static String digest(String token, String code) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest((token + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
