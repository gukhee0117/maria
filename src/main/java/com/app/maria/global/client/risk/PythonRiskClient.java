package com.app.maria.global.client.risk;

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

    public PythonRiskClient(@Qualifier("pythonRiskRestClient") RestClient client) {
        this.client = client;
    }

    public List<PythonGradeResponseDTO> fetch() {
        List<PythonGradeResponseDTO> rows;
        try {
            rows =
                    client.get()
                            .uri("/grades")
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
