package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.event.CustomerNotificationCreated;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NotificationEmailService {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    private final NotificationEmailRenderer renderer;
    private final NotificationEmailVerificationService verification;

    public NotificationEmailService(
            ObjectProvider<JavaMailSender> senders,
            @Value("${SMTP_FROM:}") String from,
            NotificationEmailRenderer renderer,
            NotificationEmailVerificationService verification) {
        this.senders = senders;
        this.from = from;
        this.renderer = renderer;
        this.verification = verification;
    }

    @Async("settlementMailExecutor")
    public void deliverSettlement(CustomerNotificationCreated event) {
        if (event.settlement() == null || event.email() == null) return;
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            log.warn("[NOTIFICATION_EMAIL_NOT_CONFIGURED] customerId={}", event.customerId());
            return;
        }
        try {
            if (!verification.isVerified(event.customerId(), event.email())) return;
        } catch (RuntimeException failure) {
            log.warn("[NOTIFICATION_EMAIL_GUARD_UNAVAILABLE] customerId={}", event.customerId());
            return;
        }
        MimeMessage message;
        try {
            NotificationEmailRenderer.Rendered body = renderer.render(event);
            message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "리턴증권");
            helper.setTo(event.email());
            helper.setSubject("[Returns] " + event.title());
            helper.setText(body.text(), body.html());
            helper.addInline(
                    "returns-logo",
                    new ClassPathResource("mail/returns-wordmark.png"),
                    "image/png");
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException failure) {
            log.error(
                    "[NOTIFICATION_EMAIL_RENDER_FAILED] customerId={} error={}",
                    event.customerId(),
                    failure.getClass().getSimpleName());
            return;
        }
        try {
            sender.send(message);
        } catch (MailException failure) {
            log.warn(
                    "[NOTIFICATION_EMAIL_FAILED] customerId={} error={}",
                    event.customerId(),
                    failure.getClass().getSimpleName());
        }
    }
}
