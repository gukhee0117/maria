package com.app.maria.domain.stockpackage.service;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** 현재가·종가 이력 조회가 함께 쓰는 종목코드 검사. */
final class TickerValidator {

    static final int MAX_TICKERS = 20;
    // 국내 상장 코드: 숫자 6자리 또는 대문자·숫자 6자리(신규 ETF 코드)
    private static final Pattern TICKER = Pattern.compile("[0-9A-Z]{6}");

    private TickerValidator() {}

    /** 종목 수(1~MAX_TICKERS)와 코드 형식을 검사하고, 앞뒤 공백을 뗀 코드를 요청 순서대로 중복 없이 돌려준다. */
    static LinkedHashSet<String> validate(List<String> tickers) {
        if (tickers == null || tickers.isEmpty() || tickers.size() > MAX_TICKERS) {
            throw new AppException(ErrorType.STOCK_PACKAGE_QUOTE_INVALID);
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : tickers) {
            String ticker = raw == null ? "" : raw.trim();
            if (!TICKER.matcher(ticker).matches()) {
                throw new AppException(ErrorType.STOCK_PACKAGE_QUOTE_INVALID);
            }
            unique.add(ticker);
        }
        return unique;
    }
}
