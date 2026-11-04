package com.app.maria.domain.fundflow.dto;

import com.app.maria.domain.fundflow.type.FundFlowTransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 원천 테이블 한 행(원화 확정 1건, 국내 주문 1건, 인출 1건). 같은 날·같은 구분끼리 서비스에서 합친다. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowTransactionRowDTO {
    private LocalDate eventDate;
    private FundFlowTransactionType type;

    /** 국내 매수·매도만 값이 있다. */
    private Long domesticProductId;

    private String productName;
    private BigDecimal qty;

    /** 체결가. 평균 매입가 재계산용이라 화면에는 내보내지 않는다. 값이 없을 수 있다. */
    private BigDecimal price;

    /** 체결금액(수수료·거래세 제외). 원화 확정·인출은 그 금액. */
    private BigDecimal amount;

    private BigDecimal feeAmount;
    private BigDecimal taxAmount;

    /** 예수금 변동액(입금 +, 출금 −). 국내 매수는 수수료 포함, 국내 매도는 수수료·거래세를 뺀 순액. */
    private BigDecimal cashAmount;

    /** 처리 시각. 같은 날 안의 실제 순서(보유 수량·평균가 재계산)에만 쓰고 화면에는 내보내지 않는다. */
    private LocalDateTime eventAt;
}
