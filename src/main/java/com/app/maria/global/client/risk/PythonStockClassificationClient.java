package com.app.maria.global.client.risk;

import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Current rule-based stock grade, independent of historical market metrics. */
@Component
public class PythonStockClassificationClient {
    private final RestClient client;
    private final RiskProperties properties;

    public PythonStockClassificationClient(
            @Qualifier("pythonRiskRestClient") RestClient client, RiskProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public record Classification(String ticker, int riskGrade, OffsetDateTime asOf, String basis) {}

    public Classification fetch(String ticker) {
        String key = properties.getServiceKey();
        if (ticker == null
                || !ticker.matches("[0-9]{6}")
                || key == null
                || key.isBlank()
                || key.indexOf('\r') >= 0
                || key.indexOf('\n') >= 0) {
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        Classification body;
        try {
            body =
                    client.get()
                            .uri("/stocks/{ticker}/classification", ticker)
                            .header("X-Risk-Service-Key", key)
                            .retrieve()
                            .body(Classification.class);
        } catch (HttpClientErrorException e) {
            throw new AppException(
                    e.getStatusCode().value() == 429
                            ? ErrorType.RISK_UPSTREAM_UNAVAILABLE
                            : ErrorType.RISK_INVALID_RESPONSE);
        } catch (RestClientException e) {
            throw new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE);
        }
        // Current stock classification contract returns only grades 1 or 2; fund grades use a
        // separate API.
        if (body == null
                || !ticker.equals(body.ticker())
                || body.riskGrade() < 1
                || body.riskGrade() > 2
                || body.asOf() == null
                || !"STOCK_RULE_CURRENT_STATUS".equals(body.basis())) {
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        return body;
    }
}
