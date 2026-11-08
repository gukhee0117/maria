package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.event.CustomerNotificationCreated;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class NotificationEmailRenderer {
    private final TemplateEngine templates;
    private final String webBaseUrl;

    public NotificationEmailRenderer(
            TemplateEngine templates,
            @Value("${CUSTOMER_WEB_BASE_URL:http://localhost:5173}") String webBaseUrl) {
        URI uri = URI.create(webBaseUrl.trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "CUSTOMER_WEB_BASE_URL에는 고객 웹의 HTTP(S) 주소를 설정해야 합니다.");
        }
        this.templates = templates;
        this.webBaseUrl = webBaseUrl.trim().replaceAll("/+$", "");
    }

    public Rendered render(CustomerNotificationCreated event) {
        var details = java.util.Objects.requireNonNull(event.settlement(), "정산 정보가 필요합니다.");
        String actionUrl = webBaseUrl + "/account";
        String actionLabel = "내 계좌에서 확인하기";
        String amount =
                new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.KOREA))
                                .format(details.getSettledAmount())
                        + "원";
        String completedAt =
                details.getCompletedAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"));
        Context context = new Context(Locale.KOREA);
        context.setVariable("title", event.title());
        context.setVariable("content", event.content());
        context.setVariable("amount", amount);
        context.setVariable("completedAt", completedAt);
        context.setVariable("actionUrl", actionUrl);
        context.setVariable("actionLabel", actionLabel);
        context.setVariable("settingsUrl", webBaseUrl + "/myinfo#notify");
        String text =
                event.title()
                        + "\n\n"
                        + event.content()
                        + "\n\n"
                        + "정산 금액: "
                        + amount
                        + "\n정산 완료 시각(KST): "
                        + completedAt
                        + "\n\n"
                        + actionLabel
                        + ": "
                        + actionUrl
                        + "\n\n"
                        + "이 메일은 수신 설정에 따라 발송되었습니다. 알림 설정: "
                        + webBaseUrl
                        + "/myinfo#notify";
        return new Rendered(text, templates.process("mail/notification", context));
    }

    public record Rendered(String text, String html) {}
}
