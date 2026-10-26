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
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 저장된 확정 세액(calc)과 구간별 내역(breakdown)을 PDF용 문자열로 바꾼다. 금액 계산은 하지 않고 표시용 곱셈·합계만 한다. */
@Component
public class TaxReliefCertViewAssembler {

    public TaxReliefCertView assemble(
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
                            label(p.getValidFrom(), p.getValidTo()),
                            won(p.getSellAmount()),
                            won(p.getGainAmount()),
                            percent(p.getWeight()),
                            won(p.getSellAmount().multiply(p.getWeight())),
                            won(p.getGainAmount().multiply(p.getWeight()))));
            totalSell = totalSell.add(p.getSellAmount());
            totalGain = totalGain.add(p.getGainAmount());
        }

        return TaxReliefCertView.builder()
                .issueNo(issueNo)
                .issuedDate(issuedAt.toLocalDate().toString())
                .holderName(holderName)
                .accountNo(account.getAccountNo())
                .openedDate(
                        account.getOpenedAt() == null
                                ? "-"
                                : account.getOpenedAt().toLocalDate().toString())
                .limitAmount(won(account.getLimitAmount()) + "원")
                .periods(periods)
                .totalSell(won(totalSell))
                .totalGain(won(totalGain))
                .totalWeightedSell(won(calc.getWeightedSell()))
                .totalWeightedGain(won(calc.getWeightedGain()))
                .weightedGain(won(calc.getWeightedGain()) + "원")
                .externalAmount(won(calc.getWeightedExternalAmount()) + "원")
                .adjustRatio(percent(calc.getAdjustRatio()))
                .finalDeduction(won(calc.getFinalDeduction()) + "원")
                .originalGain(won(calc.getOriginalGainAmount()) + "원")
                .basicDeduction(won(constant(taxRules, TaxRuleType.BASIC_DEDUCTION)) + "원")
                .taxRate(percent(constant(taxRules, TaxRuleType.TAX_RATE)))
                .finalTax(won(calc.getFinalTax()) + "원")
                .reducedByExternal(calc.getAdjustRatio().compareTo(BigDecimal.ONE) < 0)
                .build();
    }

    private String label(LocalDate from, LocalDate to) {
        return from.getMonthValue() + "~" + to.getMonthValue() + "월";
    }

    private BigDecimal constant(List<TaxRuleDTO> rules, TaxRuleType type) {
        return rules.stream()
                .filter(rule -> rule.getRuleType() == type)
                .findFirst()
                .map(TaxRuleDTO::getRuleValue)
                .orElseThrow(() -> new AppException(ErrorType.TAX_RULE_NOT_FOUND, type));
    }

    private String won(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0");
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount);
    }

    /** 0.7442 -> 74.42%, 1.0000 -> 100%. 세율 규칙값(0.22)도 같은 방식이다. */
    private String percent(BigDecimal ratio) {
        BigDecimal percent =
                ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
