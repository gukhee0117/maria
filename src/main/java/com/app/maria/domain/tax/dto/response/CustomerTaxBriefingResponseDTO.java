package com.app.maria.domain.tax.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerTaxBriefingResponseDTO {
    private Long accountId;
    private TaxScenario current;
    private ExpectedTaxScenario expected;
    private LocalDateTime calculatedAt;

    public static CustomerTaxBriefingResponseDTO of(
            Long accountId,
            TaxScenario current,
            ExpectedTaxScenario expected,
            LocalDateTime calculatedAt) {
        return CustomerTaxBriefingResponseDTO.builder()
                .accountId(accountId)
                .current(current)
                .expected(expected)
                .calculatedAt(calculatedAt)
                .build();
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxScenario {
        private BigDecimal generalTax;
        private BigDecimal riaTax;
        private BigDecimal reliefAmount;

        public static TaxScenario of(
                BigDecimal generalTax, BigDecimal riaTax, BigDecimal reliefAmount) {
            return TaxScenario.builder()
                    .generalTax(generalTax)
                    .riaTax(riaTax)
                    .reliefAmount(reliefAmount)
                    .build();
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpectedTaxScenario {
        /** 확정 매도분과 보유분 가정 매도를 합산해 기본공제를 한 번 적용한 세액. */
        private BigDecimal generalTax;

        private BigDecimal riaTax;
        private BigDecimal reliefAmount;

        /** 같은 조회 시점·규칙으로 계산한 확정 매도분 감면액. 배치 스냅샷과 시점이 다를 수 있다. */
        private BigDecimal finalizedReliefAmount;

        /** 합산 감면액 - 확정 매도분 감면액. 손실 상계 등으로 음수가 될 수 있다. */
        private BigDecimal additionalReliefAmount;

        private LocalDate expectedFinalAt;

        /** 가정 매도 대상인 잔량이 있는 입고 lot 개수. 0이면 매도 대상 없음. */
        private int holdingCount;

        public static ExpectedTaxScenario of(
                BigDecimal generalTax,
                BigDecimal riaTax,
                BigDecimal reliefAmount,
                BigDecimal additionalReliefAmount,
                LocalDate expectedFinalAt,
                int holdingCount) {
            return ExpectedTaxScenario.builder()
                    .generalTax(generalTax)
                    .riaTax(riaTax)
                    .reliefAmount(reliefAmount)
                    .additionalReliefAmount(additionalReliefAmount)
                    .expectedFinalAt(expectedFinalAt)
                    .holdingCount(holdingCount)
                    .build();
        }
    }
}
