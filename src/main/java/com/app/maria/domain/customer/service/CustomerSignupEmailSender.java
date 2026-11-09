package com.app.maria.domain.customer.service;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Signup mail must finish sending before the API reports success; notification opt-outs do not
 * apply.
 */
@Service
@Slf4j
public class CustomerSignupEmailSender {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public CustomerSignupEmailSender(
            ObjectProvider<JavaMailSender> senders, @Value("${SMTP_FROM:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    public void send(String email, String code) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank())
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_EMAIL_UNAVAILABLE);
        String validFor = SignupVerificationPolicy.codeValidityText();
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "리턴증권");
            helper.setTo(email);
            helper.setSubject("[리턴증권] 회원가입 인증번호");
            helper.setText(
                    "리턴증권 회원가입 인증번호는 "
                            + code
                            + "입니다. "
                            + validFor
                            + " 안에 입력해 주세요. 직접 요청하지 않았다면 이 메일을 무시해 주세요.",
                    "<div style=\"max-width:520px;margin:32px auto;padding:32px;font-family:sans-serif;color:#17233b;border:1px solid #e5e7eb;border-radius:12px\">"
                            + "<h2>리턴증권</h2><h3>회원가입 이메일 인증</h3><p>회원가입을 완료하려면 아래 인증번호를 입력해 주세요.</p>"
                            + "<p style=\"padding:20px;background:#f3f5f9;text-align:center;font-size:32px;letter-spacing:8px;font-weight:bold\">"
                            + code
                            + "</p>"
                            + "<p>인증번호는 <b>"
                            + validFor
                            + "</b> 동안 유효합니다.<br>다시 발송하면 이전 번호는 사용할 수 없습니다.</p>"
                            + "<p style=\"color:#667085;font-size:12px\">직접 요청하지 않았다면 이 메일을 무시해 주세요. 인증번호를 다른 사람에게 알려주지 마세요.</p></div>");
            sender.send(message);
        } catch (Exception failure) {
            log.warn("[SIGNUP_EMAIL_FAILED] error={}", failure.getClass().getSimpleName());
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_EMAIL_UNAVAILABLE);
        }
    }
}
