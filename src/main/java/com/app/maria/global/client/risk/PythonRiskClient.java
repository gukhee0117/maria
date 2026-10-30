package com.app.maria.global.client.risk;

import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PythonRiskClient {
    private final RestClient client;
    private final RiskProperties properties;

    public PythonRiskClient(
            @Qualifier("pythonRiskRestClient") RestClient client, RiskProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public List<PythonGradeResponseDTO> fetch() {
        String key = properties.getServiceKey();
        if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            // Configuration errors must not make an unauthenticated upstream request.
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        List<PythonGradeResponseDTO> rows;
        try {
            rows =
                    client.get()
                            .uri("/grades")
                            .header("X-Risk-Service-Key", key)
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            List<PythonGradeResponseDTO>>() {});
        } catch (HttpClientErrorException e) {
            throw new AppException(
                    e.getStatusCode().value() == 429
                            ? ErrorType.RISK_UPSTREAM_UNAVAILABLE
                            : ErrorType.RISK_INVALID_RESPONSE);
        } catch (RestClientException e) {
            // Never log response bodies, credentials, or raw upstream exceptions.
            throw new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE);
        }
        if (rows == null || rows.size() > 77)
            throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        HashSet<String> tickers = new HashSet<>();
        for (PythonGradeResponseDTO row : rows) {
            if (row == null) throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
            row.validate();
            if (!tickers.add(row.ticker())) throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        }
        return List.copyOf(rows);
    }
}
