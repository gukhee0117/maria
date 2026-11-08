package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockPriceHistoryResponseDTO;
import com.app.maria.global.client.securities.SecuritiesMarketClient;
import com.app.maria.global.client.securities.SecuritiesMarketClient.ClosePoint;
import com.app.maria.global.exception.AppException;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 증권사의 일별 종가를 그대로 중계한다. 기준일은 서버의 업무일이고 클라이언트가 정하지 못한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceHistoryServiceImpl implements StockPriceHistoryService {

    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 60;

    private final SecuritiesMarketClient securities;

    @Override
    public List<StockPriceHistoryResponseDTO> getHistory(List<String> tickers, int days) {
        LinkedHashSet<String> unique = TickerValidator.validate(tickers);
        int n = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        Map<String, List<ClosePoint>> history = fetchHistory(unique, n);
        return unique.stream().map(t -> toDto(t, history.get(t))).toList();
    }

    private Map<String, List<ClosePoint>> fetchHistory(LinkedHashSet<String> tickers, int days) {
        if (!securities.isActive()) {
            return Map.of(); // KIS 모드에는 일별 이력 조회가 없다
        }
        try {
            return securities.domesticHistory(
                    List.copyOf(tickers), securities.businessDate(), days);
        } catch (AppException e) {
            log.warn("종가 이력 조회 실패 type={}", e.getErrorType());
            return Map.of();
        } catch (RuntimeException e) {
            // 예상 밖 오류도 화면에는 "이력 없음"으로만 알린다. 로그에는 예외 종류만 남긴다
            log.warn("종가 이력 조회 실패 type={}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static StockPriceHistoryResponseDTO toDto(String ticker, List<ClosePoint> points) {
        if (points == null || points.isEmpty()) {
            return StockPriceHistoryResponseDTO.unavailable(ticker);
        }
        return new StockPriceHistoryResponseDTO(
                ticker,
                true,
                points.stream()
                        .map(
                                p ->
                                        new StockPriceHistoryResponseDTO.Point(
                                                p.baseDate(), plain(p.closeKrw())))
                        .toList());
    }

    /** 15980.0000 → 15980. 지수 표기(1.598E+4)가 나오지 않게 소수가 없으면 정수로 맞춘다. */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
