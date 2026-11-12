package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalCauseDTO;
import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalDTO;
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
public class FundFlowEarlyWithdrawalResponseDTO {

    private static final String BASIS =
            "분모: FINALIZED 환전(확정일이 기준일 전날까지)의 계좌 수와 확정 원금, 해지(CLOSED) 계좌 포함."
                    + " 분자: 분모 대상 환전에서 나온 인출만, 완료(COMPLETED) 인출 중 1년 미경과 원금이 포함된 배분(인출 시각이 기준일 전날까지)."
                    + " 분자는 인출액이고 분모는 확정 원금이라 금액 비율은 '확정 원금 중 1년 전에 인출된 비중'으로 읽는다."
                    + " 원인은 완료된 해지 요청에 연결된 인출이면 해지 강제 인출, 아니면 그 밖의 인출이다."
                    + " 한 계좌가 두 원인에 걸칠 수 있어 원인별 계좌 수의 합은 전체 계좌 수보다 클 수 있다";

    private LocalDate asOfDate;
    private Long finalizedAccounts;
    private BigDecimal finalizedAmount;
    private Long earlyAccounts;
    private BigDecimal earlyWithdrawnAmount;
    private BigDecimal accountShare;
    private BigDecimal amountShare;
    private List<Cause> byCause;
    private String basis;

    public FundFlowEarlyWithdrawalResponseDTO(FundFlowEarlyWithdrawalDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.finalizedAccounts = vo.getFinalizedAccounts();
        this.finalizedAmount = vo.getFinalizedAmount();
        this.earlyAccounts = vo.getEarlyAccounts();
        this.earlyWithdrawnAmount = vo.getEarlyWithdrawnAmount();
        this.accountShare = vo.getAccountShare();
        this.amountShare = vo.getAmountShare();
        this.byCause = vo.getByCause().stream().map(Cause::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Cause {
        private String cause;
        private String label;
        private Long accounts;
        private BigDecimal amount;

        public Cause(FundFlowEarlyWithdrawalCauseDTO vo) {
            this.cause = vo.getCause();
            this.label = vo.getLabel();
            this.accounts = vo.getAccounts();
            this.amount = vo.getAmount();
        }
    }
}
