package com.app.maria.domain.risk.service;

import com.app.maria.global.client.risk.PythonStockClassificationClient;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/** Use for stock-grade-only decisions; package/market metrics retain their expiry checks. */
@Service
public class CurrentStockRiskService {
    private final PythonStockClassificationClient client;

    public CurrentStockRiskService(PythonStockClassificationClient client) {
        this.client = client;
    }

    public record CurrentStockGrade(String ticker, int riskGrade, OffsetDateTime asOf) {}

    public CurrentStockGrade findCurrent(String ticker) {
        var classification = client.fetch(ticker);
        return new CurrentStockGrade(
                classification.ticker(), classification.riskGrade(), classification.asOf());
    }
}
