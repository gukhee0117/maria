package com.app.maria.domain.asset.price;

import java.util.Map;
import java.util.Set;

/** 연동 구현 빈이 없을 때만 자동 설정에서 제공하는 기본 구현. 가상 가격은 테스트에서만 쓴다. */
public class UnavailableAssetPriceProvider implements AssetPriceProvider {
    @Override
    public Map<String, Quote> getPrices(Set<String> tickers) {
        return Map.of();
    }
}
