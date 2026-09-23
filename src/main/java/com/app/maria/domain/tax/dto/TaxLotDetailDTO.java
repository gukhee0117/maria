package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 관리자가 "어느 종목을 팔아서 이 값이 나왔는지" 볼 수 있도록, 매도 건 하나하나를 표시용으로 넘긴다.
@Getter
@Builder
@AllArgsConstructor
public class TaxLotDetailDTO {
    private String productLabel;
    private LocalDate finalAt;
    private BigDecimal sellAmount;
    private BigDecimal gainAmount;
}
