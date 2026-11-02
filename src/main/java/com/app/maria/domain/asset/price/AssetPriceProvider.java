package com.app.maria.domain.asset.price;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 국내 종목 원화 현재가 조회 경계. 실제 증권사 API 계약이 확정되면 연동 빈을 추가한다. 부분 조회 불가는 ticker 누락, 전체 조회 불가는 빈 Map 또는
 * AppException(ASSET_PRICE_UNAVAILABLE)으로 표현한다. 실제 연동 구현체는 외부 응답의 종목 코드·통화·시각을 이 계약으로 검증/변환해야 한다.
 * 조회·캐시 유효기간은 실제 API 계약 확정 후 적용하며, 임의 가격을 만들어 반환하지 않는다.
 */
public interface AssetPriceProvider {
    Map<String, Quote> getPrices(Set<String> tickers);

    /** priceKrw는 1주당 원화 가격, priceAt은 제공자가 명시한 가격 기준시각(KST). */
    record Quote(BigDecimal priceKrw, LocalDateTime priceAt) {}
}
