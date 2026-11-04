package com.app.maria.global.client.risk;

import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Python risk의 패키지 변동성 계산 호출. 종목별 등급 Pull은 PythonRiskClient가 맡는다. */
@Component
public class PythonPackageRiskClient {
    /** Python이 받는 종목 수 상한. */
    public static final int MAX_ITEMS = 77;

    private final RestClient client;
    private final RiskProperties properties;

    public PythonPackageRiskClient(
            @Qualifier("pythonRiskRestClient") RestClient client, RiskProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** weight는 0 초과 1 이하의 비율이고 합계가 1이어야 한다(퍼센트가 아니다). */
    public record Item(String ticker, BigDecimal weight) {}

    private record Request(List<Item> items) {}

    /** volatility는 연 변동성 비율(0.2215 = 22.15%)이다. */
    public record Volatility(BigDecimal volatility, OffsetDateTime asOf) {}

    public Volatility fetchVolatility(List<Item> items) {
        String key = properties.getServiceKey();
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            // 설정 오류로는 인증 없는 요청을 보내지 않는다.
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        Volatility body;
        try {
            body =
                    client.post()
                            .uri("/package/volatility")
                            .header("X-Risk-Service-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new Request(items))
                            .retrieve()
                            .body(Volatility.class);
        } catch (HttpClientErrorException e) {
            throw new AppException(
                    e.getStatusCode().value() == 429
                            ? ErrorType.RISK_UPSTREAM_UNAVAILABLE
                            : ErrorType.RISK_INVALID_RESPONSE);
        } catch (RestClientException e) {
            // 응답 본문·인증값·원본 예외는 기록하지 않는다.
            throw new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE);
        }
        if (body == null
                || body.volatility() == null
                || body.volatility().signum() < 0
                || body.asOf() == null) {
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        return body;
    }
}
