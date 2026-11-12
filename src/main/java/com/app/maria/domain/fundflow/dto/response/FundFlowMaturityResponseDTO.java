package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowMaturityDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMaturityMonthDTO;
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
public class FundFlowMaturityResponseDTO {

    private static final String BASIS =
            "FINALIZED 환전 기준(확정일이 기준일 전날까지), 만기월 = 확정일 + 1년의 월, 해지(CLOSED) 계좌 제외(해지 신청 중은 포함),"
                    + " 전량 인출된 건도 건수·확정 원금에 포함, 원 단위. 묶임·만기 분류는 업무시각 - 1년 경계로 판정하며,"
                    + " 2/29 확정분은 기존 plusYears(1) 판정과 하루 어긋날 수 있다."
                    + " 혜택이 취소된(benefit = IMPOSSIBLE) 계좌의 잔액은 1년 유지 조건과 무관해 따로 집계한다:"
                    + " lockedAmount·lockedAccounts·maturedRemainingAmount와 months[].remainingAmount는 혜택이 살아 있는 계좌만,"
                    + " canceledRemainingAmount·canceledAccounts는 취소된 계좌의 잔액(묶임·만기 상관없이 전부)이다."
                    + " left_amount가 없는 대상 건도 확정 원금에 포함하고 lotsWithoutLeftAmount로 센다(정상이면 0)."
                    + " excludedClosedPaidAmount는 해지 계좌의 확정 원금 합";

    private LocalDate asOfDate;
    private LocalDate firstMaturityMonth;
    private BigDecimal lockedAmount;
    private Long lockedAccounts;
    private BigDecimal maturedRemainingAmount;
    private BigDecimal canceledRemainingAmount;
    private Long canceledAccounts;
    private Long lotsWithoutLeftAmount;
    private BigDecimal excludedClosedPaidAmount;
    private List<Month> months;
    private String basis;

    public FundFlowMaturityResponseDTO(FundFlowMaturityDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.firstMaturityMonth = vo.getFirstMaturityMonth();
        this.lockedAmount = vo.getLockedAmount();
        this.lockedAccounts = vo.getLockedAccounts();
        this.maturedRemainingAmount = vo.getMaturedRemainingAmount();
        this.canceledRemainingAmount = vo.getCanceledRemainingAmount();
        this.canceledAccounts = vo.getCanceledAccounts();
        this.lotsWithoutLeftAmount = vo.getLotsWithoutLeftAmount();
        this.excludedClosedPaidAmount = vo.getExcludedClosedPaidAmount();
        this.months = vo.getMonths().stream().map(Month::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Month {
        private LocalDate maturityMonth;
        private Long lots;
        private Long accounts;
        private BigDecimal paidAmount;
        private BigDecimal remainingAmount;
        private BigDecimal canceledRemainingAmount;

        public Month(FundFlowMaturityMonthDTO vo) {
            this.maturityMonth = vo.getMaturityMonth();
            this.lots = vo.getLots();
            this.accounts = vo.getAccounts();
            this.paidAmount = vo.getPaidAmount();
            this.remainingAmount = vo.getRemainingAmount();
            this.canceledRemainingAmount = vo.getCanceledRemainingAmount();
        }
    }
}
