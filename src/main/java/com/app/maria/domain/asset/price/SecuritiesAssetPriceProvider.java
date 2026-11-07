package com.app.maria.domain.asset.price;

import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 증권사 저장 현재가 조회. 가격 변화를 바로 반영하도록 응답을 별도로 캐시하지 않는다. */
@Component
public class SecuritiesAssetPriceProvider implements AssetPriceProvider {
    private final RestClient client;
    private final BusinessClockService businessClockService;

    public SecuritiesAssetPriceProvider(
            @Qualifier("assetPriceRestClient") RestClient client,
            BusinessClockService businessClockService) {
        this.client = client;
        this.businessClockService = businessClockService;
    }

    @Override
    public Map<String, Quote> getPrices(Set<String> tickers) {
        List<String> requested = tickers.stream().sorted().toList();
        Map<String, Quote> prices = new HashMap<>();
        LocalDate asOf = businessClockService.now().toLocalDate();
        try {
            for (int offset = 0; offset < requested.size(); offset += 50) {
                List<String> batch =
                        requested.subList(offset, Math.min(offset + 50, requested.size()));
                var response =
                        client.get()
                                .uri(
                                        builder ->
                                                builder.path("/api/domestic-prices")
                                                        .queryParam(
                                                                "tickers", String.join(",", batch))
                                                        .queryParam("asOf", asOf)
                                                        .build())
                                .accept(MediaType.APPLICATION_JSON)
                                .retrieve()
                                .body(
                                        new ParameterizedTypeReference<
                                                ApiResponseDTO<List<StoredPrice>>>() {});
                if (response == null || response.getData() == null) throw unavailable();
                for (StoredPrice row : response.getData()) {
                    if (row == null || !batch.contains(row.ticker())) continue;
                    if (row.priceKrw() == null
                            || row.priceKrw().signum() <= 0
                            || row.priceAt() == null) continue;
                    // 같은 종목의 중복 가격은 오염된 응답으로 보고 전체 조회를 실패시킨다.
                    if (prices.putIfAbsent(row.ticker(), new Quote(row.priceKrw(), row.priceAt()))
                            != null) throw unavailable();
                }
            }
            return Map.copyOf(prices);
        } catch (RestClientException e) {
            // 토큰·URL·원본 응답을 예외에 포함하지 않는다.
            throw unavailable();
        }
    }

    private AppException unavailable() {
        return new AppException(ErrorType.ASSET_PRICE_UNAVAILABLE);
    }

    public record StoredPrice(
            String ticker,
            BigDecimal priceKrw,
            LocalDate baseDate,
            LocalDateTime priceAt,
            String source) {}
}
