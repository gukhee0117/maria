package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.sellorder.dto.response.SellLimitStatusDTO;
import java.math.BigDecimal;

public interface SellLimitService {

    boolean isWithinSellLimit(Long accountId, BigDecimal orderAmount);

    /** 현재 사용 가능한 매도 한도를 조회한다. 행 락 없음 — 조회 전용. */
    BigDecimal getRemainingLimit(Long accountId);

    /** 고객 본인 RIA 계좌의 한도·누적 사용액·남은 한도. 거래 가능 상태(OPENED)가 아니면 매도 주문과 같은 예외를 던진다. */
    SellLimitStatusDTO getStatusByCustomerId(Long customerId);
}
