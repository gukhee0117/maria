package com.app.maria.global.client.securities;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 고객 주문의 체결을 증권사 거래로 남긴다. 주문 트랜잭션이 커밋된 뒤에 부르며, 최대 횟수까지 다시 시도해도 안 되면 AppException을 던진다(호출한 {@code
 * SecuritiesOrderRecorder}가 받아 나중에 다시 보낸다). 같은 maria 주문번호는 증권사가 중복으로 기록하지 않아 재전송해도 안전하다. 실패 메시지에는
 * 호스트·응답 본문을 넣지 않는다.
 */
@Slf4j
@Component
public class SecuritiesTradeClient {
    private static final long[] DEFAULT_BACKOFF_MS = {200, 500};

    private final RestClient client;
    private final String secret;
    private final int maxAttempts;
    private final Duration totalBudget;
    private final long[] backoffMs;

    @Autowired
    public SecuritiesTradeClient(
            @Qualifier("assetPriceRestClient") RestClient client,
            @Value("${custom.internal.secret:}") String secret,
            @Value("${custom.securities-trade.max-attempts:3}") int maxAttempts,
            @Value("${custom.securities-trade.total-budget-ms:4000}") long totalBudgetMs) {
        this(client, secret, maxAttempts, totalBudgetMs, DEFAULT_BACKOFF_MS);
    }

    SecuritiesTradeClient(
            RestClient client,
            String secret,
            int maxAttempts,
            long totalBudgetMs,
            long[] backoffMs) {
        this.client = client;
        this.secret = secret;
        this.maxAttempts = maxAttempts;
        this.totalBudget = Duration.ofMillis(totalBudgetMs);
        this.backoffMs = backoffMs;
    }

    public void record(
            String ciHash,
            DomesticOrderSide side,
            String ticker,
            BigDecimal qty,
            BigDecimal price,
            LocalDateTime executedAt,
            Long mariaOrderId) {
        Map<String, Object> body =
                Map.of(
                        "ciHash", ciHash,
                        "side", side.name(),
                        "ticker", ticker,
                        "qty", qty,
                        "price", price,
                        "executedAt", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(executedAt),
                        "mariaOrderId", mariaOrderId);
        long startedAt = System.nanoTime();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                client.post()
                        .uri("/api/internal/domestic-trades")
                        .header("X-Internal-Secret", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RestClientResponseException e) {
                // 4xx는 같은 요청을 다시 보내도 같은 결과라 바로 포기한다
                if (e.getStatusCode().is4xxClientError()) {
                    log.warn(
                            "증권사 거래 기록 거절 orderId={} status={}",
                            mariaOrderId,
                            e.getStatusCode().value());
                    throw failure();
                }
                log.warn("증권사 거래 기록 오류 orderId={} 시도 {}/{}", mariaOrderId, attempt, maxAttempts);
            } catch (RestClientException e) {
                log.warn("증권사 거래 기록 응답 없음 orderId={} 시도 {}/{}", mariaOrderId, attempt, maxAttempts);
            }
            if (attempt == maxAttempts
                    || Duration.ofNanos(System.nanoTime() - startedAt).compareTo(totalBudget)
                            >= 0) {
                break;
            }
            pause(backoffMs[Math.min(attempt - 1, backoffMs.length - 1)]);
        }
        throw failure();
    }

    private static AppException failure() {
        // 호스트·URL·응답 본문을 예외에 싣지 않는다(원인도 달지 않는다)
        return new AppException(ErrorType.STOCK_ORDER_SECURITIES_RECORD_FAILED);
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
