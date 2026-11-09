package com.app.maria.domain.stockpackage.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 종목 하나의 일별 종가 이력. points는 날짜 오름차순이고 마지막이 업무일까지(당일 포함) 저장된 가장 최근 영업일 종가다. 현재가가 아니라 저장된 종가라서 화면의
 * 현재가와 조금 다를 수 있다. 이력을 받지 못한 종목은 available=false이고 points는 비어 있다.
 */
@Getter
@AllArgsConstructor
public class StockPriceHistoryResponseDTO {
    private final String ticker;
    private final boolean available;
    private final List<Point> points;

    public static StockPriceHistoryResponseDTO unavailable(String ticker) {
        return new StockPriceHistoryResponseDTO(ticker, false, List.of());
    }

    /** date는 종가 기준일, close는 종가(원). 소수가 없으면 정수로 보인다. */
    public record Point(LocalDate date, BigDecimal close) {}
}
