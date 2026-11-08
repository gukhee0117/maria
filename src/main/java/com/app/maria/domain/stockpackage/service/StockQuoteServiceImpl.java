package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockQuoteResponseDTO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.KisDomesticPriceClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockQuoteServiceImpl implements StockQuoteService {

    static final int MAX_TICKERS = 20;
    // 국내 상장 코드: 숫자 6자리 또는 대문자·숫자 6자리(신규 ETF 코드)
    private static final Pattern TICKER = Pattern.compile("[0-9A-Z]{6}");

    private final KisDomesticPriceClient kisDomesticPriceClient;

    @Override
    public List<StockQuoteResponseDTO> getQuotes(List<String> tickers) {
        return findQuotes(tickers).stream().map(StockQuoteResponseDTO::new).toList();
    }

    @Override
    public List<StockQuoteVO> findQuotes(List<String> tickers) {
        LinkedHashSet<String> unique = validateTickers(tickers);
        Map<String, DomesticQuote> quotes = fetchQuotes(unique);
        return unique.stream().map(ticker -> toVo(ticker, quotes.get(ticker))).toList();
    }

    /** 종목 수(1~MAX_TICKERS)와 코드 형식을 검사하고, 앞뒤 공백을 뗀 코드를 요청 순서대로 중복 없이 돌려준다. 현재가·종가 이력 조회가 함께 쓴다. */
    static LinkedHashSet<String> validateTickers(List<String> tickers) {
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

    private Map<String, DomesticQuote> fetchQuotes(LinkedHashSet<String> tickers) {
        try {
            return kisDomesticPriceClient.getQuotes(List.copyOf(tickers));
        } catch (AppException e) {
            log.warn("현재가 일괄 조회 실패 type={}", e.getErrorType());
            return Map.of();
        }
    }

    private StockQuoteVO toVo(String ticker, DomesticQuote q) {
        if (q == null) {
            log.warn("현재가 조회 실패 ticker={}", ticker);
            return StockQuoteVO.unavailable(ticker);
        }
        return new StockQuoteVO(
                ticker, true, q.price(), q.previousClose(), q.upperLimit(), q.lowerLimit());
    }
}
