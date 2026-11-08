package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockPriceHistoryResponseDTO;
import java.util.List;

public interface StockPriceHistoryService {

    /**
     * 요청한 순서대로 종목별 최근 days개 영업일 종가를 돌려준다(업무일 당일까지). 이력을 받지 못한 종목은 available=false로 표시한다. days는
     * 1~60으로 맞춘다.
     */
    List<StockPriceHistoryResponseDTO> getHistory(List<String> tickers, int days);
}
