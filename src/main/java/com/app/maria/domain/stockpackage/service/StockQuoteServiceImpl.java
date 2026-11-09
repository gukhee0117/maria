package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockQuoteResponseDTO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.KisDomesticPriceClient;
import com.app.maria.global.exception.AppException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockQuoteServiceImpl implements StockQuoteService {

    private final KisDomesticPriceClient kisDomesticPriceClient;

    @Override
    public List<StockQuoteResponseDTO> getQuotes(List<String> tickers) {
        return findQuotes(tickers).stream().map(StockQuoteResponseDTO::new).toList();
    }

    @Override
    public List<StockQuoteVO> findQuotes(List<String> tickers) {
        LinkedHashSet<String> unique = TickerValidator.validate(tickers);
        Map<String, DomesticQuote> quotes = fetchQuotes(unique);
        return unique.stream().map(ticker -> toVo(ticker, quotes.get(ticker))).toList();
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
