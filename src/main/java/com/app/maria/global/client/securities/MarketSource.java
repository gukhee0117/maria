package com.app.maria.global.client.securities;

/** 시세·환율을 어디서 읽는지. securities = 증권사(return-securities) 저장값(기본), kis = KIS·한국수출입은행 API. */
public enum MarketSource {
    KIS,
    SECURITIES;

    public static MarketSource from(String raw) {
        return raw == null || raw.isBlank() ? SECURITIES : valueOf(raw.trim().toUpperCase());
    }
}
