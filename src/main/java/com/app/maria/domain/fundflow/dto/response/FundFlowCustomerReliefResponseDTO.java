package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowCustomerReliefDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowCustomerReliefResponseDTO {

    private static final String NOTICE = "표시용 예상값이며 확정 세액은 확정신고 시 계산됩니다";

    private LocalDate asOfDate;
    private boolean hasAccount;
    private String benefit;
    private String statusCode;
    private String statusLabel;
    private BigDecimal weightedSell;
    private BigDecimal adjustRatio;
    private BigDecimal finalDeduction;
    private BigDecimal finalTax;
    private BigDecimal generalTax;
    private BigDecimal reliefAmount;
    private LocalDateTime calculatedAt;
    private String notice;

    public FundFlowCustomerReliefResponseDTO(FundFlowCustomerReliefDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.hasAccount = vo.isHasAccount();
        this.benefit = vo.getBenefit();
        this.statusCode = vo.getStatusCode();
        this.statusLabel = vo.getStatusLabel();
        this.weightedSell = vo.getWeightedSell();
        this.adjustRatio = vo.getAdjustRatio();
        this.finalDeduction = vo.getFinalDeduction();
        this.finalTax = vo.getFinalTax();
        this.generalTax = vo.getGeneralTax();
        this.reliefAmount = vo.getReliefAmount();
        this.calculatedAt = vo.getCalculatedAt();
        this.notice = NOTICE;
    }
}
