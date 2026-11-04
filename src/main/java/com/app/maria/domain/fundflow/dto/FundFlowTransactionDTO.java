package com.app.maria.domain.fundflow.dto;

import com.app.maria.domain.fundflow.type.FundFlowTransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 같은 날·같은 구분을 합친 거래 한 줄. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowTransactionDTO {
    private LocalDate date;
    private FundFlowTransactionType type;

    /** 예수금 변동액 합계(입금 +, 출금 −) */
    private BigDecimal amount;

    /** 이 거래까지 반영한 예수금 */
    private BigDecimal cashAfter;

    /** 국내 매수·매도: "OO 외 N". 나머지는 null. */
    private String title;

    /** 종목별 내역. 국내 매수·매도만 채운다. */
    private List<FundFlowTransactionRowDTO> details;
}
