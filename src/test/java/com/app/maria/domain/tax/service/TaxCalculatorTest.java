package com.app.maria.domain.tax.service;

import static com.app.maria.domain.tax.fixture.TaxFixtures.allSeedRules;
import static com.app.maria.domain.tax.fixture.TaxFixtures.externalBuy;
import static com.app.maria.domain.tax.fixture.TaxFixtures.lot;
import static com.app.maria.domain.tax.fixture.TaxFixtures.reliefRate;
import static com.app.maria.domain.tax.fixture.TaxFixtures.reliefRates;
import static com.app.maria.domain.tax.fixture.TaxFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaxCalculatorTest {

    private final TaxCalculator calculator = new TaxCalculator();

    @Test
    @DisplayName("골든 시나리오 - CLAUDE.md §3 검증 예시와 일치한다")
    void 골든시나리오() {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                        lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("43000000");
        assertThat(result.getWeightedGain()).isEqualByComparingTo("27800000");
        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("32000000");
    }

    @Test
    @DisplayName("구간 경계 - 5/31은 100%, 6/1은 80%")
    void 구간경계_5월말_6월초() {
        TaxCalculationResultDTO may =
                calculator.calculate(
                        List.of(lot(LocalDate.of(2026, 5, 31), "10000000", "100", "1000", "40")),
                        allSeedRules(),
                        List.of(),
                        false);
        TaxCalculationResultDTO jun =
                calculator.calculate(
                        List.of(lot(LocalDate.of(2026, 6, 1), "10000000", "100", "1000", "40")),
                        allSeedRules(),
                        List.of(),
                        false);

        assertThat(may.getWeightedSell()).isEqualByComparingTo("10000000");
        assertThat(may.getWeightedGain()).isEqualByComparingTo("6000000");

        assertThat(jun.getWeightedSell()).isEqualByComparingTo("8000000");
        assertThat(jun.getWeightedGain()).isEqualByComparingTo("4800000");

        assertThat(may.getOriginalGainAmount()).isEqualByComparingTo("6000000");
        assertThat(jun.getOriginalGainAmount()).isEqualByComparingTo("6000000");
    }

    @Test
    @DisplayName("구간 경계 - 7/31은 80%, 8/1은 50%")
    void 구간경계_7월말_8월초() {
        TaxCalculationResultDTO jul =
                calculator.calculate(
                        List.of(lot(LocalDate.of(2026, 7, 31), "10000000", "100", "1000", "40")),
                        allSeedRules(),
                        List.of(),
                        false);
        TaxCalculationResultDTO aug =
                calculator.calculate(
                        List.of(lot(LocalDate.of(2026, 8, 1), "10000000", "100", "1000", "40")),
                        allSeedRules(),
                        List.of(),
                        false);

        assertThat(jul.getWeightedSell()).isEqualByComparingTo("8000000");
        assertThat(jul.getWeightedGain()).isEqualByComparingTo("4800000");

        assertThat(aug.getWeightedSell()).isEqualByComparingTo("5000000");
        assertThat(aug.getWeightedGain()).isEqualByComparingTo("3000000");
    }

    @Test
    @DisplayName("과세연도 첫날·마지막날도 가중치를 찾는다")
    void 구간경계_연초_연말() {
        TaxCalculationResultDTO first =
                calculator.calculate(
                        List.of(lot(LocalDate.of(2026, 1, 1), "10000000", "100", "1000", "40")),
                        allSeedRules(),
                        List.of(),
                        false);
        TaxCalculationResultDTO last =
                calculator.calculate(
                        List.of(lot(LocalDate.of(2026, 12, 31), "10000000", "100", "1000", "40")),
                        allSeedRules(),
                        List.of(),
                        false);

        assertThat(first.getWeightedSell()).isEqualByComparingTo("10000000");
        assertThat(last.getWeightedSell()).isEqualByComparingTo("5000000");
    }

    @ParameterizedTest(name = "{0} 매도 → 가중치 {3}")
    @CsvSource({
        "2026-01-01, 10000000, 6000000, 100%",
        "2026-02-14, 10000000, 6000000, 100%",
        "2026-03-10, 10000000, 6000000, 100%",
        "2026-05-30, 10000000, 6000000, 100%",
        "2026-05-31, 10000000, 6000000, 100%",
        "2026-06-01,  8000000, 4800000, 80%",
        "2026-06-15,  8000000, 4800000, 80%",
        "2026-07-01,  8000000, 4800000, 80%",
        "2026-07-31,  8000000, 4800000, 80%",
        "2026-08-01,  5000000, 3000000, 50%",
        "2026-09-20,  5000000, 3000000, 50%",
        "2026-11-11,  5000000, 3000000, 50%",
        "2026-12-31,  5000000, 3000000, 50%",
    })
    @DisplayName("시드의 RELIEF_RATE 구간이 매도결제일별로 정확히 적용된다")
    void 매도일별_구간가중치(
            String sellDate,
            String expectedWeightedSell,
            String expectedWeightedGain,
            String weightLabel) {

        List<SellLotDTO> lots =
                List.of(lot(LocalDate.parse(sellDate), "10000000", "100", "1000", "40"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo(expectedWeightedSell);
        assertThat(result.getWeightedGain()).isEqualByComparingTo(expectedWeightedGain);
        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("6000000");
    }

    @Test
    @DisplayName("손익통산 - 손실 lot이 이익 lot과 상계된다")
    void 손익통산() {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 3, 10), "5000000", "100", "1000", "100"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("15000000");
        assertThat(result.getWeightedGain()).isEqualByComparingTo("15000000");
        assertThat(result.getWeightedSell()).isEqualByComparingTo("35000000");
    }

    @Test
    @DisplayName("전부 손실이면 양도소득이 음수로 남는다")
    void 전부손실() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "5000000", "100", "1000", "100"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("-5000000");
        assertThat(result.getWeightedGain()).isEqualByComparingTo("-5000000");
    }

    @Test
    @DisplayName("매도 lot이 없으면 전부 0")
    void 매도없음() {
        TaxCalculationResultDTO result =
                calculator.calculate(List.of(), allSeedRules(), List.of(), false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("0");
        assertThat(result.getWeightedGain()).isEqualByComparingTo("0");
        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("가중치 구간 밖 날짜면 예외")
    void 가중치없는날짜() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2027, 1, 5), "10000000", "100", "1000", "40"));

        assertThatThrownBy(() -> calculator.calculate(lots, allSeedRules(), List.of(), false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TAX_RULE_NOT_FOUND);
    }

    @Test
    @DisplayName("RELIEF_RATE가 아닌 규칙이 섞여 있어도 가중치만 골라 쓴다")
    void 다른규칙이_섞여도_가중치만_사용한다() {
        List<TaxRuleDTO> mixed =
                List.of(
                        rule(
                                TaxRuleType.DEPOSIT_LIMIT,
                                "50000000",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(9999, 12, 31)),
                        rule(
                                TaxRuleType.BASIC_DEDUCTION,
                                "2500000",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(9999, 12, 31)),
                        rule(
                                TaxRuleType.TAX_RATE,
                                "0.22",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(9999, 12, 31)),
                        reliefRate("100", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31)),
                        reliefRate("80", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31)),
                        reliefRate("50", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31)));

        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));

        TaxCalculationResultDTO result = calculator.calculate(lots, mixed, List.of(), false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("5000000");
        assertThat(result.getWeightedGain()).isEqualByComparingTo("3000000");
    }

    @Test
    @DisplayName("RELIEF_RATE가 하나도 없으면 예외")
    void 가중치규칙없음() {
        List<TaxRuleDTO> onlyConstants =
                List.of(
                        rule(
                                TaxRuleType.BASIC_DEDUCTION,
                                "2500000",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(9999, 12, 31)));

        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "10000000", "100", "1000", "40"));

        assertThatThrownBy(() -> calculator.calculate(lots, onlyConstants, List.of(), false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TAX_RULE_NOT_FOUND);
    }

    @Test
    @DisplayName("가중치 목록이 비어 있으면 예외")
    void 가중치목록없음() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "10000000", "100", "1000", "40"));

        assertThatThrownBy(() -> calculator.calculate(lots, List.of(), List.of(), false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TAX_RULE_NOT_FOUND);
    }

    @Test
    @DisplayName("골든 시나리오 - 외부 순매수까지 §3 검증 예시와 일치한다")
    void 골든시나리오_외부순매수() {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                        lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));

        List<ExternalBuyDTO> external =
                List.of(
                        externalBuy(LocalDate.of(2026, 6, 15), "20000000"),
                        externalBuy(LocalDate.of(2026, 9, 20), "-10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("11000000");
        assertThat(result.getWeightedSell()).isEqualByComparingTo("43000000");
        assertThat(result.getWeightedGain()).isEqualByComparingTo("27800000");
        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("32000000");
    }

    @Test
    @DisplayName("외부 거래가 없으면 순매수는 0")
    void 외부거래_없음() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "{0} 매수 1,000만 → 가중 {1}")
    @CsvSource({
        "2026-01-01, 10000000",
        "2026-05-31, 10000000",
        "2026-06-01,  8000000",
        "2026-07-31,  8000000",
        "2026-08-01,  5000000",
        "2026-12-31,  5000000"
    })
    @DisplayName("외부 순매수도 거래일별 구간 가중치가 그대로 적용된다")
    void 외부_구간가중치(String tradeDate, String expected) {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(externalBuy(LocalDate.parse(tradeDate), "10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("매수·매도가 섞이면 각 거래일 가중치를 적용해 상계한다")
    void 외부_매수매도_상계() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(
                        externalBuy(LocalDate.of(2026, 3, 10), "10000000"),
                        externalBuy(LocalDate.of(2026, 6, 15), "10000000"),
                        externalBuy(LocalDate.of(2026, 9, 20), "-10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("13000000");
    }

    @Test
    @DisplayName("외부에서 순매도면 조정할 순매수가 없으므로 0으로 본다")
    void 외부_순매도는_0() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(
                        externalBuy(LocalDate.of(2026, 3, 10), "20000000"),
                        externalBuy(LocalDate.of(2026, 3, 10), "-30000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("매수·매도가 정확히 상쇄되면 0")
    void 외부_완전상쇄() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(
                        externalBuy(LocalDate.of(2026, 6, 15), "10000000"),
                        externalBuy(LocalDate.of(2026, 6, 15), "-10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("금액은 소수점 2자리로 반올림된다")
    void 외부_금액_스케일() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(externalBuy(LocalDate.of(2026, 9, 20), "1000000.005"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("500000.00");
        assertThat(result.getWeightedExternalAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("매도 lot이 없어도 외부 순매수는 독립적으로 집계된다")
    void 외부_매도없어도_집계() {
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(List.of(), allSeedRules(), external, false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("0");
        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("10000000");
    }

    @Test
    @DisplayName("외부 거래일이 가중치 구간 밖이면 예외")
    void 외부_가중치없는날짜() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2027, 1, 5), "10000000"));

        assertThatThrownBy(() -> calculator.calculate(lots, allSeedRules(), external, false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TAX_RULE_NOT_FOUND)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorData())
                                        .asString()
                                        .contains("RELIEF_RATE"));
    }

    @Test
    @DisplayName("골든 시나리오 - 조정비율이 §3 검증 예시(74.4%)와 일치한다")
    void 조정비율_골든시나리오() {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                        lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));
        List<ExternalBuyDTO> external =
                List.of(
                        externalBuy(LocalDate.of(2026, 6, 15), "20000000"),
                        externalBuy(LocalDate.of(2026, 9, 20), "-10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.7442");
    }

    @Test
    @DisplayName("외부 순매수가 없으면 조정비율은 1")
    void 조정비율_외부없음() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("외부 순매도만 있어도 조정비율은 1 - 순매수가 0으로 잘리기 때문")
    void 조정비율_외부순매도() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(externalBuy(LocalDate.of(2026, 3, 10), "-10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("외부 순매수가 가중매도금액과 같으면 조정비율은 0")
    void 조정비율_경계_동일() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "30000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("30000000");
        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("30000000");
        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("외부 순매수가 가중매도금액보다 크면 음수가 아니라 0이 된다")
    void 조정비율_초과분은_0으로_잘린다() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "50000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("매도가 없으면 0으로 나누지 않고 조정비율 0을 반환한다")
    void 조정비율_매도없음() {
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(List.of(), allSeedRules(), external, false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("0");
        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.0000");
    }

    @ParameterizedTest(name = "가중매도 4,300만 / 외부 {0} → {1}")
    @CsvSource({
        "        0, 1.0000",
        " 10750000, 0.7500",
        " 21500000, 0.5000",
        " 43000000, 0.0000",
        "  1000000, 0.9767"
    })
    @DisplayName("조정비율 = 1 - (외부 순매수 / 가중매도금액)")
    void 조정비율_산식(String externalAmount, String expected) {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                        lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));
        List<ExternalBuyDTO> external =
                List.of(externalBuy(LocalDate.of(2026, 3, 10), externalAmount));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("43000000");
        assertThat(result.getAdjustRatio()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("조정비율은 tax_calculation.ratio(DECIMAL(7,4))에 맞춰 소수점 4자리로 확정된다")
    void 조정비율_스케일() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "10000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getAdjustRatio().scale()).isEqualTo(4);
        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("나눗셈 몫이 반올림 경계여도 마지막에 한 번만 반올림한다")
    void 조정비율_반올림_경계_상향() {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                        lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "2150"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedSell()).isEqualByComparingTo("43000000");
        assertThat(result.getAdjustRatio()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("반올림 경계가 중간 자리일 때도 마지막 반올림 결과를 따른다")
    void 조정비율_반올림_경계_중간자리() {
        List<SellLotDTO> lots =
                List.of(
                        lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                        lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                        lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "6450"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.9999");
    }

    @Test
    @DisplayName("조정비율은 1을 넘지 않는다")
    void 조정비율_상한() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"));
        List<ExternalBuyDTO> external =
                List.of(
                        externalBuy(LocalDate.of(2026, 3, 10), "10000000"),
                        externalBuy(LocalDate.of(2026, 3, 10), "-40000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), external, false);

        assertThat(result.getWeightedExternalAmount()).isEqualByComparingTo("0");
        assertThat(result.getAdjustRatio()).isEqualByComparingTo("1.0000");
    }

    private static final List<SellLotDTO> GOLDEN_LOTS =
            List.of(
                    lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                    lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                    lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40"));

    private static final List<ExternalBuyDTO> GOLDEN_EXTERNAL =
            List.of(
                    externalBuy(LocalDate.of(2026, 6, 15), "20000000"),
                    externalBuy(LocalDate.of(2026, 9, 20), "-10000000"));

    @Test
    @DisplayName("골든 시나리오 - 최종공제액과 최종세액이 §3 검증 예시와 일치한다")
    void 골든시나리오_공제와세액() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.7442");
        assertThat(result.getFinalDeduction()).isEqualByComparingTo("20688760.00");
        assertThat(result.getFinalTax()).isEqualByComparingTo("1938472.80");
    }

    @Test
    @DisplayName("최종공제액 = 조정전공제액 × 조정비율")
    void 최종공제액_산식() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, false);

        assertThat(result.getFinalDeduction())
                .isEqualByComparingTo(
                        result.getWeightedGain()
                                .multiply(result.getAdjustRatio())
                                .setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("최종세액 = (비가중 총양도소득 − 기본공제 − 최종공제액) × 세율")
    void 최종세액_산식() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, false);

        BigDecimal expected =
                result.getOriginalGainAmount()
                        .subtract(new BigDecimal("2500000"))
                        .subtract(result.getFinalDeduction())
                        .multiply(new BigDecimal("0.22"))
                        .setScale(2, RoundingMode.HALF_UP);
        assertThat(result.getFinalTax()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("혜택 배제 계좌는 공제 없이 기본공제·세율만 적용된다")
    void 혜택배제() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, true);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.0000");
        assertThat(result.getFinalDeduction()).isEqualByComparingTo("0");
        assertThat(result.getFinalTax()).isEqualByComparingTo("6490000.00");
    }

    @Test
    @DisplayName("혜택 배제여도 조정전공제액은 그대로 남는다 - 받았다면 얼마였는지 보여주기 위해")
    void 혜택배제_조정전공제액은_유지() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, true);

        assertThat(result.getWeightedGain()).isEqualByComparingTo("27800000");
        assertThat(result.getWeightedSell()).isEqualByComparingTo("43000000");
    }

    @Test
    @DisplayName("혜택 배제는 정상 계산보다 세액이 크다")
    void 혜택배제_세액이_더_크다() {
        BigDecimal normal =
                calculator
                        .calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, false)
                        .getFinalTax();
        BigDecimal excluded =
                calculator
                        .calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, true)
                        .getFinalTax();

        assertThat(excluded).isGreaterThan(normal);
    }

    @Test
    @DisplayName("외부 순매수가 없으면 조정전공제액이 전액 공제된다")
    void 외부순매수_없으면_전액공제() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), List.of(), false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("1.0000");
        assertThat(result.getFinalDeduction()).isEqualByComparingTo("27800000.00");
        assertThat(result.getFinalTax()).isEqualByComparingTo("374000.00");
    }

    @Test
    @DisplayName("조정비율이 0이면 공제도 0이다")
    void 조정비율0이면_공제0() {
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "50000000"));

        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), external, false);

        assertThat(result.getAdjustRatio()).isEqualByComparingTo("0.0000");
        assertThat(result.getFinalDeduction()).isEqualByComparingTo("0");
        assertThat(result.getFinalTax()).isEqualByComparingTo("6490000.00");
    }

    @Test
    @DisplayName("양도소득이 기본공제보다 작으면 세액은 0이다")
    void 양도소득이_기본공제보다_작으면_세액0() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "12000000", "100", "1000", "100"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getOriginalGainAmount()).isEqualByComparingTo("2000000");
        assertThat(result.getFinalTax()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("전부 손실이면 공제도 세액도 0이다")
    void 전부손실이면_공제0_세액0() {
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 3, 10), "5000000", "100", "1000", "100"));

        TaxCalculationResultDTO result =
                calculator.calculate(lots, allSeedRules(), List.of(), false);

        assertThat(result.getWeightedGain()).isNegative();
        assertThat(result.getFinalDeduction()).isEqualByComparingTo("0");
        assertThat(result.getFinalTax()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("매도가 없으면 공제도 세액도 0이다")
    void 매도없으면_공제0_세액0() {
        TaxCalculationResultDTO result =
                calculator.calculate(List.of(), allSeedRules(), GOLDEN_EXTERNAL, false);

        assertThat(result.getFinalDeduction()).isEqualByComparingTo("0");
        assertThat(result.getFinalTax()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("기본공제 규칙이 없으면 예외")
    void 기본공제규칙_없음() {
        assertThatThrownBy(() -> calculator.calculate(GOLDEN_LOTS, reliefRates(), List.of(), false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TAX_RULE_NOT_FOUND)
                .hasFieldOrPropertyWithValue("errorData", TaxRuleType.BASIC_DEDUCTION);
    }

    @Test
    @DisplayName("세율 규칙이 없으면 예외")
    void 세율규칙_없음() {
        List<TaxRuleDTO> withoutTaxRate =
                allSeedRules().stream()
                        .filter(rule -> TaxRuleType.TAX_RATE != rule.getRuleType())
                        .toList();

        assertThatThrownBy(
                        () -> calculator.calculate(GOLDEN_LOTS, withoutTaxRate, List.of(), false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TAX_RULE_NOT_FOUND)
                .hasFieldOrPropertyWithValue("errorData", TaxRuleType.TAX_RATE);
    }

    @Test
    @DisplayName("공제액과 세액은 소수점 2자리로 확정된다")
    void 공제와세액_스케일() {
        TaxCalculationResultDTO result =
                calculator.calculate(GOLDEN_LOTS, allSeedRules(), GOLDEN_EXTERNAL, false);

        assertThat(result.getFinalDeduction().scale()).isEqualTo(2);
        assertThat(result.getFinalTax().scale()).isEqualTo(2);
    }
}
