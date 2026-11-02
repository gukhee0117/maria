package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowCustomerSummaryDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
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
public class FundFlowCustomerSummaryResponseDTO {

    private static final String BASIS = "원화로 확정된 금액 중 국내 매수에 쓴 비율";
    private static final String NO_ACCOUNT_MESSAGE = "아직 RIA 계좌가 없어요";
    private static final String NO_INFLOW_MESSAGE = "아직 원화로 확정된 금액이 없어요";

    private LocalDate asOfDate;
    private boolean hasAccount;
    private BigDecimal soldForeignAmount;
    private BigDecimal krwInflowAmount;
    private BigDecimal domesticBuyAmount;
    private BigDecimal domesticBuyFeeAmount;
    private BigDecimal cashBalance;
    private BigDecimal reinvestRatio;
    private String message;
    private String basis;

    public FundFlowCustomerSummaryResponseDTO(FundFlowCustomerSummaryDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.hasAccount = vo.isHasAccount();
        this.soldForeignAmount = vo.getSoldForeignAmount();
        this.krwInflowAmount = vo.getKrwInflowAmount();
        this.domesticBuyAmount = vo.getDomesticBuyAmount();
        this.domesticBuyFeeAmount = vo.getDomesticBuyFeeAmount();
        this.cashBalance = vo.getCashBalance();
        this.reinvestRatio = vo.getReinvestRatio();
        this.message = buildMessage();
        this.basis = BASIS;
    }

    private String buildMessage() {
        if (!hasAccount) {
            return NO_ACCOUNT_MESSAGE;
        }
        if (reinvestRatio == null) {
            return NO_INFLOW_MESSAGE;
        }
        return "해외주식을 판 돈의 "
                + reinvestRatio.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP)
                + "%를 다시 국내에 투자했어요";
    }
}
