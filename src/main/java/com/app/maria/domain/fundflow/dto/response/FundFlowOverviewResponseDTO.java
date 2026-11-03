package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
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
public class FundFlowOverviewResponseDTO {

    private static final String BASIS = "분모: 계좌 개설자 Σ확정 유입. 재투자율 = Σ국내 매수 체결금액 ÷ Σ확정 유입";

    private LocalDate asOfDate;
    private Long openedAccounts;
    private BigDecimal inflowTotal;
    private BigDecimal domesticBuyTotal;
    private BigDecimal reinvestRatio;
    private BigDecimal netInvestRatio;
    private String basis;

    public FundFlowOverviewResponseDTO(FundFlowOverviewDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.openedAccounts = vo.getOpenedAccounts();
        this.inflowTotal = vo.getInflowTotal();
        this.domesticBuyTotal = vo.getDomesticBuyTotal();
        this.reinvestRatio = vo.getReinvestRatio();
        this.netInvestRatio = vo.getNetInvestRatio();
        this.basis = BASIS;
    }
}
