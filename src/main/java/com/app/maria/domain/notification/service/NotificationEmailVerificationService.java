package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.mapper.CustomerNotificationSettingsMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Security TTL uses real time. Proof is bound to the customer's current address. */
@Service
@Slf4j
public class NotificationEmailVerificationService {
    private final SeedEmailVerificationExemptions seedExemptions;
    private final CustomerNotificationSettingsMapper contacts;
    private final StringRedisTemplate redis;
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private static final String PREFIX = "notification:email:";
    private static final DefaultRedisScript<Long> RESERVE =
            new DefaultRedisScript<>(
                    """
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            if tonumber(redis.call('GET', KEYS[3]) or '0') >= 10 or tonumber(redis.call('GET', KEYS[4]) or '0') >= 10 then return 0 end
            redis.call('SET', KEYS[1], '1', 'EX', 60)
            redis.call('SET', KEYS[2], '1', 'EX', 60)
            if redis.call('INCR', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], 3600) end
            if redis.call('INCR', KEYS[4]) == 1 then redis.call('EXPIRE', KEYS[4], 86400) end
            return 1
            """,
                    Long.class);
    private static final DefaultRedisScript<Long> SAVE =
            new DefaultRedisScript<>(
                    """
            redis.call('HSET', KEYS[1], 'salt', ARGV[1], 'digest', ARGV[2], 'attempts', 0)
            redis.call('EXPIRE', KEYS[1], 300)
            return 1
            """,
                    Long.class);
    private static final DefaultRedisScript<Long> VERIFY =
            new DefaultRedisScript<>(
                    """
            if redis.call('HGET', KEYS[1], 'salt') ~= ARGV[1] then return 0 end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if redis.call('HGET', KEYS[1], 'digest') == ARGV[2] then
                redis.call('DEL', KEYS[1])
                redis.call('SET', KEYS[2], '1', 'EX', 2592000)
                return 1
            end
            if attempts >= 5 then redis.call('DEL', KEYS[1]) end
            return 0
            """,
                    Long.class);
    private static final DefaultRedisScript<Long> CANCEL =
            new DefaultRedisScript<>(
                    """
            if redis.call('HGET', KEYS[1], 'salt') == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """,
                    Long.class);

    public NotificationEmailVerificationService(
            CustomerNotificationSettingsMapper contacts,
            StringRedisTemplate redis,
            ObjectProvider<JavaMailSender> senders,
            @Value("${SMTP_FROM:}") String from,
            Clock clock) {
        this(contacts, redis, senders, from, clock, new SeedEmailVerificationExemptions());
    }

    @Autowired
    public NotificationEmailVerificationService(
            CustomerNotificationSettingsMapper contacts,
            StringRedisTemplate redis,
            ObjectProvider<JavaMailSender> senders,
            @Value("${SMTP_FROM:}") String from,
            Clock clock,
            SeedEmailVerificationExemptions seedExemptions) {
        this.seedExemptions = seedExemptions;
        this.contacts = contacts;
        this.redis = redis;
        this.senders = senders;
        this.from = from;
        this.clock = clock;
    }

    public record Status(boolean verified, String maskedEmail) {}

    public record Challenge(String maskedEmail, Instant expiresAt, Instant resendAvailableAt) {}

    public Status status(Long customerId) {
        String email = contacts.selectEmail(customerId);
        return new Status(isRegisteredEmailVerified(customerId, email), mask(email));
    }

    public boolean isVerified(Long customerId, String email) {
        if (email == null || email.isBlank() || !email.equals(contacts.selectEmail(customerId))) {
            return false;
        }
        return isRegisteredEmailVerified(customerId, email);
    }

    /** Internal only: the address must come from the customer's registered contact row. */
    boolean isRegisteredEmailVerified(Long customerId, String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        // Only known seed IDs need an extra auth lookup. Changing their original address removes
        // the exemption.
        if (seedExemptions.containsCustomer(customerId)
                && seedExemptions.matches(
                        customerId, contacts.selectLocalLoginId(customerId), email)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(key("verified", customerId, email)));
        } catch (DataAccessException failure) {
            log.warn("NOTIFICATION_EMAIL_VERIFICATION_UNAVAILABLE customerId={}", customerId);
            throw new AppException(
                    ErrorType.NOTIFICATION_EMAIL_VERIFICATION_UNAVAILABLE, null, failure);
        }
    }

    public Challenge send(Long customerId) {
        String email = requireEmail(customerId);
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank())
            throw new AppException(ErrorType.NOTIFICATION_EMAIL_VERIFICATION_UNAVAILABLE);
        if (!Long.valueOf(1)
                .equals(
                        redis.execute(
                                RESERVE,
                                List.of(
                                        PREFIX + "request:customer:" + customerId,
                                        PREFIX
                                                + "request:address:"
                                                + hash(email.toLowerCase(Locale.ROOT)),
                                        PREFIX + "request:hour:" + customerId,
                                        PREFIX
                                                + "request:day:"
                                                + hash(email.toLowerCase(Locale.ROOT))))))
            throw new AppException(ErrorType.NOTIFICATION_EMAIL_VERIFICATION_RATE_LIMITED);
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String salt = UUID.randomUUID().toString(), challenge = key("challenge", customerId, email);
        redis.execute(SAVE, List.of(challenge), salt, hash(salt + ":" + code));
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, "리턴증권");
            helper.setTo(email);
            helper.setSubject("[리턴증권] 이메일 알림 수신 주소 확인");
            helper.setText(
                    "이메일 알림 수신 주소 확인 인증번호는 " + code + "입니다. 5분 안에 입력해 주세요. 직접 요청하지 않았다면 무시해 주세요.");
            sender.send(message);
        } catch (Exception failure) {
            redis.execute(CANCEL, List.of(challenge), salt);
            throw new AppException(ErrorType.NOTIFICATION_EMAIL_VERIFICATION_UNAVAILABLE);
        }
        Instant now = clock.instant();
        return new Challenge(mask(email), now.plusSeconds(300), now.plusSeconds(60));
    }

    public Status confirm(Long customerId, String code) {
        String email = requireEmail(customerId), challenge = key("challenge", customerId, email);
        Object salt = redis.opsForHash().get(challenge, "salt");
        if (salt == null
                || !Long.valueOf(1)
                        .equals(
                                redis.execute(
                                        VERIFY,
                                        List.of(challenge, key("verified", customerId, email)),
                                        salt.toString(),
                                        hash(salt + ":" + code))))
            throw new AppException(ErrorType.NOTIFICATION_EMAIL_VERIFICATION_INVALID);
        return new Status(true, mask(email));
    }

    private String requireEmail(Long customerId) {
        String email = contacts.selectEmail(customerId);
        if (email == null || email.isBlank())
            throw new AppException(ErrorType.CUSTOMER_NOTIFICATION_EMAIL_REQUIRED);
        return email;
    }

    private static String mask(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.lastIndexOf('@');
        return at > 0 ? email.substring(0, 1) + "***" + email.substring(at) : "***";
    }

    static String key(String kind, Long customerId, String email) {
        return PREFIX + kind + ":" + customerId + ":" + hash(email);
    }

    static String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
