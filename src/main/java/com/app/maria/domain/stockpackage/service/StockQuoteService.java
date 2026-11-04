package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockQuoteResponseDTO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import java.util.List;

public interface StockQuoteService {

    /** 요청한 순서대로 현재가를 돌려준다. 시세를 받지 못한 종목은 available=false로 표시한다. */
    List<StockQuoteResponseDTO> getQuotes(List<String> tickers);

    /** getQuotes와 같지만 서비스 안에서 쓰는 VO로 돌려준다(주문 처리용). */
    List<StockQuoteVO> findQuotes(List<String> tickers);
}
