package com.app.maria.domain.stockpackage.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** 주문 결과. accountAmount는 주문 뒤 예수금, buyingPower는 그중 가정산을 뺀 주문 가능 금액이다. */
public record StockOrderResponseDTO(
        List<StockOrderResultDTO> orders, BigDecimal accountAmount, BigDecimal buyingPower) {}
