package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SellLotDTO {
    private Long accountId;
    private Long orderId;
    private Long inboundDetailId;
    private BigDecimal purchaseFxRate;
    private BigDecimal purchasePrice;
    private BigDecimal sellQty;
    private LocalDate finalAt;
    private BigDecimal finalAmount;
    // 관리자가 "어느 종목을 팔아서 이 값이 나왔는지" 볼 수 있도록 표시용으로만 들고 다닌다.
    private String productLabel;
}
// 매도 (수량×단가×매도시 환율)	매도금액(원화)
// 취득 (수량×단가×매수당시환율)
// 취득원가(원화)	양도소득
