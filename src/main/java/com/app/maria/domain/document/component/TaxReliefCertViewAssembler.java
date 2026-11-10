package com.app.maria.domain.document.component;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 저장된 확정 세액(calc)과 구간별 내역(breakdown)을 PDF용 문자열로 바꾼다. 세액은 다시 계산하지 않고, 구간 행의 가중 금액만 표시용으로 곱한다. */
@Component
public class TaxReliefCertViewAssembler {

    public TaxReliefCertView assemble(
            int taxYear,
            String issueNo,
            LocalDateTime issuedAt,
            String holderName,
            AccountDTO account,
            TaxCalculationDTO calc,
            TaxBreakdownDTO breakdown,
            List<TaxRuleDTO> taxRules) {
        List<TaxReliefCertView.Period> periods = new ArrayList<>();
        BigDecimal totalSell = BigDecimal.ZERO;
        BigDecimal totalGain = BigDecimal.ZERO;
        for (TaxPeriodBreakdownDTO p : breakdown.getPeriodBreakdown()) {
            periods.add(
                    new TaxReliefCertView.Period(
                            DocumentFormats.label(p.getValidFrom(), p.getValidTo()),
                            DocumentFormats.won(p.getSellAmount()),
                            DocumentFormats.won(p.getGainAmount()),
                            DocumentFormats.percent(p.getWeight()),
                            DocumentFormats.won(p.getSellAmount().multiply(p.getWeight())),
                            DocumentFormats.won(p.getGainAmount().multiply(p.getWeight()))));
            totalSell = totalSell.add(p.getSellAmount());
            totalGain = totalGain.add(p.getGainAmount());
        }

        return TaxReliefCertView.builder()
                .taxYear(taxYear)
                .issueNo(issueNo)
                .issuedDate(issuedAt.toLocalDate().toString())
                .holderName(holderName)
                .accountNo(account.getAccountNo())
                .openedDate(
                        account.getOpenedAt() == null
                                ? "-"
                                : account.getOpenedAt().toLocalDate().toString())
                .limitAmount(DocumentFormats.won(account.getLimitAmount()) + "원")
                .periods(periods)
                .totalSell(DocumentFormats.won(totalSell))
                .totalGain(DocumentFormats.won(totalGain))
                .totalWeightedSell(DocumentFormats.won(calc.getWeightedSell()))
                .totalWeightedGain(DocumentFormats.won(calc.getWeightedGain()))
                .weightedGain(DocumentFormats.won(calc.getWeightedGain()) + "원")
                .externalAmount(DocumentFormats.won(calc.getWeightedExternalAmount()) + "원")
                .adjustRatio(DocumentFormats.percent(calc.getAdjustRatio()))
                .finalDeduction(DocumentFormats.won(calc.getFinalDeduction()) + "원")
                .originalGain(DocumentFormats.won(calc.getOriginalGainAmount()) + "원")
                .basicDeduction(
                        DocumentFormats.won(constant(taxRules, TaxRuleType.BASIC_DEDUCTION)) + "원")
                .taxRate(DocumentFormats.percent(constant(taxRules, TaxRuleType.TAX_RATE)))
                .finalTax(DocumentFormats.won(calc.getFinalTax()) + "원")
                .reducedByExternal(calc.getAdjustRatio().compareTo(BigDecimal.ONE) < 0)
                .build();
    }

    private BigDecimal constant(List<TaxRuleDTO> rules, TaxRuleType type) {
        return rules.stream()
                .filter(rule -> rule.getRuleType() == type)
                .findFirst()
                .map(TaxRuleDTO::getRuleValue)
                .orElseThrow(() -> new AppException(ErrorType.TAX_RULE_NOT_FOUND, type));
    }
}
