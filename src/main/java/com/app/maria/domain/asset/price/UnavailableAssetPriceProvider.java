package com.app.maria.domain.asset.price;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 연동 전 기본 구현. 실제 API adapter 도입 시 이 빈을 교체한다. 가상 가격은 테스트에서만 쓴다. */
@Component
public class UnavailableAssetPriceProvider implements AssetPriceProvider {
    @Override
    public Map<String, Quote> getPrices(Set<String> tickers) {
        return Map.of();
    }
}
