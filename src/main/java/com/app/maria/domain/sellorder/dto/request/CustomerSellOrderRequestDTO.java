package com.app.maria.domain.sellorder.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 고객 매도 신청. 종목 하나와 총수량만 받고, 어느 입고분(lot)부터 팔지는 서버가 오래된 순서(FIFO)로 정한다. 여러 종목은 종목마다 따로 신청한다. */
@Getter
@Setter
@NoArgsConstructor
public class CustomerSellOrderRequestDTO {

    @NotNull(message = "종목 ID를 입력하세요.")
    private Long foreignProductId;

    @NotNull(message = "매도 수량을 입력하세요.")
    @DecimalMin(value = "0", inclusive = false, message = "매도 수량은 0보다 커야 합니다.")
    private BigDecimal sellQty;
}
