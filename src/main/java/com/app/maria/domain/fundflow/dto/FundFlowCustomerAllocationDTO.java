package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowCustomerAllocationDTO {

    private LocalDate asOfDate;
    private boolean hasAccount;

    /** 보유 종목 매입금액 합계 */
    private BigDecimal holdingsCost;

    private BigDecimal cashBalance;

    /** 보유 종목 매입금액 + 예수금 */
    private BigDecimal totalCost;

    /** 국내 주식 / ETF·펀드 / 예수금 */
    private List<FundFlowBucketDTO> buckets;

    /** 주식 업종 상위 3 (점유율은 보유 종목 매입금액 대비) */
    private List<FundFlowBucketDTO> sectors;
}
