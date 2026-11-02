package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
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
public class FundFlowReliefResponseDTO {

    private static final String DEFINITION_VERSION = "draft-1";
    private static final String BASIS =
            "일반 과세 세액 = 세율 × max(0, 비가중 총양도소득 − 기본공제). 감면액 = 일반 과세 세액 − 최종세액. 예상값(표시용 스냅샷)";

    private LocalDate asOfDate;
    private String definitionVersion;
    private String scope;
    private Long accounts;
    private BigDecimal generalTaxTotal;
    private BigDecimal finalTaxTotal;
    private BigDecimal reliefTotal;
    private Long accountsWithRelief;
    private BigDecimal investPerRelief;
    private String basis;

    public FundFlowReliefResponseDTO(FundFlowReliefDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.definitionVersion = DEFINITION_VERSION;
        this.scope = vo.getScope();
        this.accounts = vo.getAccounts();
        this.generalTaxTotal = vo.getGeneralTaxTotal();
        this.finalTaxTotal = vo.getFinalTaxTotal();
        this.reliefTotal = vo.getReliefTotal();
        this.accountsWithRelief = vo.getAccountsWithRelief();
        this.investPerRelief = vo.getInvestPerRelief();
        this.basis = BASIS;
    }
}
