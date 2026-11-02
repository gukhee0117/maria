package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthlyDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 관리자(전체 합산)와 고객(본인 계좌) 월별 흐름이 함께 쓴다. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowMonthlyResponseDTO {

    private static final String BASIS =
            "domesticBuyAmount는 체결금액+수수료, domesticSellAmount는 체결금액−수수료−거래세 (예수금 순액 기준)";

    private LocalDate asOfDate;
    private List<Month> months;
    private String basis;

    public FundFlowMonthlyResponseDTO(FundFlowMonthlyDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.months = vo.getMonths().stream().map(Month::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Month {
        private LocalDate flowMonth;
        private BigDecimal foreignSellAmount;
        private BigDecimal krwInflowAmount;
        private BigDecimal domesticBuyAmount;
        private BigDecimal domesticSellAmount;
        private BigDecimal withdrawalAmount;
        private BigDecimal cashBalanceBegin;
        private BigDecimal cashBalanceEnd;
        private boolean partial;

        public Month(FundFlowMonthDTO vo) {
            this.flowMonth = vo.getFlowMonth();
            this.foreignSellAmount = vo.getForeignSellAmount();
            this.krwInflowAmount = vo.getKrwInflowAmount();
            this.domesticBuyAmount = vo.getDomesticBuyAmount();
            this.domesticSellAmount = vo.getDomesticSellAmount();
            this.withdrawalAmount = vo.getWithdrawalAmount();
            this.cashBalanceBegin = vo.getCashBalanceBegin();
            this.cashBalanceEnd = vo.getCashBalanceEnd();
            this.partial = vo.isPartial();
        }
    }
}
