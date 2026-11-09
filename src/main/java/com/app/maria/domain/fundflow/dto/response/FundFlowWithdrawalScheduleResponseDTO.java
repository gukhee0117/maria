package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalLotDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalScheduleDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
public class FundFlowWithdrawalScheduleResponseDTO {

    private static final String BASIS =
            "납입일은 원화 환전 대금결제일(krw_exchange.final_at)이고 인출 가능일은 납입일 + 1년이에요. 같은 납입일은 합쳐서 보여줘요. 인출액은 FIFO 배분(withdrawal_allocation) 기준";

    private LocalDate businessDate;
    private boolean hasAccount;
    private List<Lot> lots;
    private String basis;

    public FundFlowWithdrawalScheduleResponseDTO(FundFlowWithdrawalScheduleDTO vo) {
        this.businessDate = vo.getBusinessDate();
        this.hasAccount = vo.isHasAccount();
        this.lots = vo.getLots().stream().map(Lot::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Lot {
        private LocalDate paidDate;
        private LocalDate maturityDate;
        private BigDecimal paidAmount;
        private BigDecimal withdrawnAmount;
        private BigDecimal remainingAmount;
        private boolean matured;

        public Lot(FundFlowWithdrawalLotDTO vo) {
            this.paidDate = vo.getPaidDate();
            this.maturityDate = vo.getMaturityDate();
            this.paidAmount = vo.getPaidAmount();
            this.withdrawnAmount = vo.getWithdrawnAmount();
            this.remainingAmount = vo.getRemainingAmount();
            this.matured = vo.isMatured();
        }
    }
}
