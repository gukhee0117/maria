package com.app.maria.domain.tax.dto.response;

import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.type.TaxScale;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/**
 * 고객 화면의 세액 "계산 근거" 창용. 스냅샷이 아니라 지금 입력으로 다시 계산한 값이다. 그래서 같은 시각에 저장된 스냅샷(tax/briefing,
 * fund-flow/relief)과 새 정산·외부 매수 때문에 숫자가 다를 수 있으니 한 화면에 섞어 보여 주지 않는다. 표시용 예상값이고 확정 세액은 확정신고 때 계산된다.
 * 금액은 소수 둘째 자리로 반올림했고 구간별 값은 구간마다 따로 반올림해서 합이 몇 원 어긋날 수 있다. 공식은 세액 계산 5단계 그대로다:
 * 가중매도금액(weightedSell), 조정전공제액(preAdjustDeduction), 다른 계좌 순매수 가중합(weightedNetPurchase, 0 미만 없음),
 * 조정비율(adjustRatio, 0 이하면 최종공제 0), 최종공제액(finalDeduction), 가중치 없는 총 양도소득(totalGain)에서
 * 기본공제(basicDeduction)와 최종공제를 빼 세율을 곱한 최종 세액(finalTax). benefit이 IMPOSSIBLE(reliefExcluded=true)이면
 * 조기인출 등으로 혜택이 취소된 계좌라 조정비율과 최종공제가 0이다.
 */
@Getter
public class CustomerTaxBasisResponseDTO {
    private final Long accountId;
    private final BenefitType benefit;
    private final boolean reliefExcluded;
    private final BigDecimal weightedSell;
    private final BigDecimal preAdjustDeduction;
    private final BigDecimal weightedNetPurchase;
    private final BigDecimal adjustRatio;
    private final BigDecimal finalDeduction;
    private final BigDecimal totalGain;
    private final BigDecimal basicDeduction;
    private final BigDecimal generalTax;
    private final BigDecimal finalTax;
    private final BigDecimal reliefAmount;
    private final List<Band> bands;
    private final LocalDateTime calculatedAt;

    private CustomerTaxBasisResponseDTO(
            Long accountId,
            BenefitType benefit,
            TaxCalculationResultDTO result,
            List<TaxPeriodBreakdownDTO> periods,
            BigDecimal basicDeduction,
            BigDecimal generalTax,
            LocalDateTime calculatedAt) {
        this.accountId = accountId;
        this.benefit = benefit;
        this.reliefExcluded = BenefitType.isReliefExcluded(benefit);
        this.weightedSell = TaxScale.AMOUNT.round(result.getWeightedSell());
        this.preAdjustDeduction = TaxScale.AMOUNT.round(result.getWeightedGain());
        this.weightedNetPurchase = TaxScale.AMOUNT.round(result.getWeightedExternalAmount());
        this.adjustRatio = result.getAdjustRatio();
        this.finalDeduction = TaxScale.AMOUNT.round(result.getFinalDeduction());
        this.totalGain = TaxScale.AMOUNT.round(result.getOriginalGainAmount());
        this.basicDeduction = basicDeduction;
        this.generalTax = TaxScale.AMOUNT.round(generalTax);
        this.finalTax = TaxScale.AMOUNT.round(result.getFinalTax());
        this.reliefAmount = this.generalTax.subtract(this.finalTax).max(BigDecimal.ZERO);
        this.bands = periods.stream().map(Band::new).toList();
        this.calculatedAt = calculatedAt;
    }

    public static CustomerTaxBasisResponseDTO of(
            Long accountId,
            BenefitType benefit,
            TaxCalculationResultDTO result,
            List<TaxPeriodBreakdownDTO> periods,
            BigDecimal basicDeduction,
            BigDecimal generalTax,
            LocalDateTime calculatedAt) {
        return new CustomerTaxBasisResponseDTO(
                accountId, benefit, result, periods, basicDeduction, generalTax, calculatedAt);
    }

    /**
     * 가중치 구간 한 줄. weightRate는 1.0이 100%이고, sellAmount·gain·externalNetBuy는 가중치를 곱하기 전 원금액이다. 구간 날짜는
     * validFrom·validTo가 정확하고 fromMonth·toMonth는 화면 표시용 월 번호다.
     */
    @Getter
    public static class Band {
        private final LocalDate validFrom;
        private final LocalDate validTo;
        private final int fromMonth;
        private final int toMonth;
        private final BigDecimal weightRate;
        private final BigDecimal sellAmount;
        private final BigDecimal gain;
        private final BigDecimal externalNetBuy;

        private Band(TaxPeriodBreakdownDTO period) {
            this.validFrom = period.getValidFrom();
            this.validTo = period.getValidTo();
            this.fromMonth = period.getValidFrom().getMonthValue();
            this.toMonth = period.getValidTo().getMonthValue();
            this.weightRate = period.getWeight();
            this.sellAmount = period.getSellAmount();
            this.gain = period.getGainAmount();
            this.externalNetBuy = period.getExternalNetBuyAmount();
        }
    }
}
