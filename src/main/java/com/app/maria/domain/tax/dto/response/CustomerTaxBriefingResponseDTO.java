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
        private BigDecimal generalTax;
        private BigDecimal riaTax;
        private BigDecimal reliefAmount;
        private BigDecimal additionalReliefAmount;
        private LocalDate expectedFinalAt;

        public static ExpectedTaxScenario of(
                BigDecimal generalTax,
                BigDecimal riaTax,
                BigDecimal reliefAmount,
                BigDecimal additionalReliefAmount,
                LocalDate expectedFinalAt) {
            return ExpectedTaxScenario.builder()
                    .generalTax(generalTax)
                    .riaTax(riaTax)
                    .reliefAmount(reliefAmount)
                    .additionalReliefAmount(additionalReliefAmount)
                    .expectedFinalAt(expectedFinalAt)
                    .build();
        }
    }
}
