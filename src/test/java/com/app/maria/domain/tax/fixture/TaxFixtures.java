package com.app.maria.domain.tax.fixture;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TaxFixtures {

    private TaxFixtures() {}

    public static List<TaxRuleDTO> reliefRates() {
        return List.of(
                reliefRate("100", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31)),
                reliefRate("80", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31)),
                reliefRate("50", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31)));
    }

    public static List<TaxRuleDTO> allSeedRules() {
        return List.of(
                rule(
                        TaxRuleType.DEPOSIT_LIMIT,
                        "50000000",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(9999, 12, 31)),
                rule(
                        TaxRuleType.HOLDING_PERIOD,
                        "1",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(9999, 12, 31)),
                reliefRate("100", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31)),
                reliefRate("80", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31)),
                reliefRate("50", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31)),
                rule(
                        TaxRuleType.BASIC_DEDUCTION,
                        "2500000",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(9999, 12, 31)),
                rule(
                        TaxRuleType.TAX_RATE,
                        "0.22",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(9999, 12, 31)));
    }

    public static TaxRuleDTO reliefRate(String value, LocalDate validFrom, LocalDate validTo) {
        return rule(TaxRuleType.RELIEF_RATE, value, validFrom, validTo);
    }

    public static TaxRuleDTO rule(
            TaxRuleType ruleType, String value, LocalDate validFrom, LocalDate validTo) {
        return new TaxRuleDTO(null, ruleType, new BigDecimal(value), validFrom, validTo);
    }

    public static ExternalBuyDTO externalBuy(LocalDate tradeDate, String netBuyAmount) {
        return ExternalBuyDTO.builder()
                .tradeDate(tradeDate)
                .netBuyAmount(new BigDecimal(netBuyAmount))
                .build();
    }

    public static SellLotDTO lot(
            LocalDate finalAt,
            String finalAmount,
            String purchasePrice,
            String purchaseFxRate,
            String sellQty) {
        return SellLotDTO.builder()
                .finalAt(finalAt)
                .finalAmount(new BigDecimal(finalAmount))
                .purchasePrice(new BigDecimal(purchasePrice))
                .purchaseFxRate(new BigDecimal(purchaseFxRate))
                .sellQty(new BigDecimal(sellQty))
                .build();
    }
}
