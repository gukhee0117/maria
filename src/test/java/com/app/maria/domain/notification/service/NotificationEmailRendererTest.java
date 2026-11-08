package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.*;

import com.app.maria.domain.notification.dto.SettlementEmailDetails;
import com.app.maria.domain.notification.event.CustomerNotificationCreated;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class NotificationEmailRendererTest {
    static TemplateEngine templates() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @Test
    void settlementRendersCustomerSummary() {
        var event =
                new CustomerNotificationCreated(
                        1L,
                        "정산이 완료됐어요",
                        "매도 대금이 원화로 정산됐어요. 내 계좌에서 내역을 확인해 주세요.",
                        true,
                        "test@example.com",
                        new SettlementEmailDetails(
                                new BigDecimal("1234567"), LocalDateTime.of(2026, 11, 8, 10, 30)));
        var result =
                new NotificationEmailRenderer(templates(), "http://localhost:5173").render(event);
        assertThat(result.html())
                .contains(
                        "1,234,567원",
                        "2026.11.08 10:30:00",
                        "내 계좌에서 확인하기",
                        "http://localhost:5173/account",
                        "cid:returns-logo");
        assertThat(result.html()).doesNotContain("배치 번호");
        assertThat(result.text()).contains("정산 금액: 1,234,567원", "http://localhost:5173/account");
    }

    @Test
    void settlementEscapesUntrustedTextAndLinksToAccount() {
        var result =
                new NotificationEmailRenderer(templates(), "https://returns.example/app/")
                        .render(
                                new CustomerNotificationCreated(
                                        1L,
                                        "<script>alert(1)</script>",
                                        "<img src=x onerror=alert(1)>",
                                        true,
                                        "test@example.com",
                                        new SettlementEmailDetails(
                                                BigDecimal.ONE,
                                                LocalDateTime.of(2026, 11, 8, 12, 0))));
        assertThat(result.html())
                .contains("&lt;script&gt;", "&lt;img", "https://returns.example/app/account");
        assertThat(result.html()).doesNotContain("<script>");
    }

    @Test
    void largeWonAmountKeepsBigDecimalPrecision() {
        var event =
                new CustomerNotificationCreated(
                        1L,
                        "완료",
                        "내용",
                        true,
                        "test@example.com",
                        new SettlementEmailDetails(
                                new BigDecimal("9007199254740993"),
                                LocalDateTime.of(2026, 11, 8, 10, 0)));
        assertThat(
                        new NotificationEmailRenderer(templates(), "https://returns.example")
                                .render(event)
                                .text())
                .contains("9,007,199,254,740,993원");
    }

    @Test
    void unsafeActionUrlIsRejected() {
        for (String base :
                java.util.List.of(
                        "javascript:alert(1)",
                        "https://user:password@example.com",
                        "https://example.com/#x")) {
            assertThatThrownBy(() -> new NotificationEmailRenderer(templates(), base))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
