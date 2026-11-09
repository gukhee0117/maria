package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.global.client.risk.PythonStockClassificationClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Current stock status cache, independent of historical-price expiry. */
@Service
public class CurrentStockRiskService {
    // Defensive memory bound for this cache, independent of the number of listed stocks.
    private static final int MAX_CACHED_TICKERS = 77;
    private static final long CACHE_TTL_SECONDS = 60;
    private final PythonStockClassificationClient client;
    private final RiskMarketClock clock;
    private final Map<String, Entry> cache = new HashMap<>();

    private record Entry(CurrentStockGrade grade, Instant receivedAt) {}

    public CurrentStockRiskService(PythonStockClassificationClient client, RiskMarketClock clock) {
        this.client = client;
        this.clock = clock;
    }

    public record CurrentStockGrade(String ticker, int riskGrade, OffsetDateTime asOf) {}

    public CurrentStockGrade findCurrent(String ticker) {
        Instant now = clock.now().toInstant();
        synchronized (cache) {
            Entry entry = cache.get(ticker);
            if (entry != null
                    && !now.isBefore(entry.receivedAt())
                    && now.isBefore(entry.receivedAt().plusSeconds(CACHE_TTL_SECONDS)))
                return entry.grade();
            cache.remove(ticker);
        }
        // Network I/O must not block cache hits or requests for other tickers.
        var classification = client.fetch(ticker);
        if (Duration.between(classification.asOf().toInstant(), now)
                        .abs()
                        .compareTo(Duration.ofMinutes(2))
                > 0) throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
        var result =
                new CurrentStockGrade(
                        classification.ticker(), classification.riskGrade(), classification.asOf());
        synchronized (cache) {
            Entry current = cache.get(ticker);
            // An older in-flight request must not overwrite a newer successful response.
            if (current != null && current.receivedAt().isAfter(now)) return current.grade();
            if (cache.size() >= MAX_CACHED_TICKERS && !cache.containsKey(ticker)) cache.clear();
            cache.put(ticker, new Entry(result, now));
        }
        return result;
    }
}
