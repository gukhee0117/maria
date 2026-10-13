package com.app.maria.domain.settlement.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SettlementCalculatorTest {

    private final SettlementCalculator calculator = new SettlementCalculator();

    @ParameterizedTest(name = "{0}")
    @MethodSource("exchangeRateScenarios")
    @DisplayName("확정환율 상승·하락·동일 시 최종 원화금액을 계산한다")
    void calculateFinalAmountAppliesFinalRate(
            String scenario,
            String provisionalAmount,
            String settlementFxRate,
            String finalRate,
            String expectedAmount) {
        BigDecimal result =
                calculator.calculateFinalAmount(
                        new BigDecimal(provisionalAmount),
                        new BigDecimal(settlementFxRate),
                        new BigDecimal(finalRate));

        assertThat(result).isEqualByComparingTo(expectedAmount);
        assertThat(result.scale()).isZero();
    }

    @Test
    @DisplayName("나눗셈이 반복소수여도 외화 scale 8을 적용해 최종금액을 계산한다")
    void calculateFinalAmountHandlesRepeatingDecimal() {
        BigDecimal result =
                calculator.calculateFinalAmount(
                        new BigDecimal("100"), new BigDecimal("3"), new BigDecimal("2"));

        assertThat(result).isEqualByComparingTo("67");
        assertThat(result.scale()).isZero();
    }

    @Test
    @DisplayName("최종 원화금액은 원 단위에서 HALF_UP 반올림한다")
    void calculateFinalAmountRoundsHalfUp() {
        BigDecimal result =
                calculator.calculateFinalAmount(
                        new BigDecimal("1.005"), BigDecimal.ONE, BigDecimal.ONE);

        assertThat(result).isEqualByComparingTo("1");
        assertThat(result.scale()).isZero();
    }

    @ParameterizedTest(name = "{0}이 null이면 계산을 차단한다")
    @MethodSource("nullInputs")
    @DisplayName("계산 입력값이 null이면 SettlementCalculationException을 반환한다")
    void calculateFinalAmountRejectsNullInput(
            String fieldName,
            BigDecimal provisionalAmount,
            BigDecimal settlementFxRate,
            BigDecimal finalRate) {
        assertThatThrownBy(
                        () ->
                                calculator.calculateFinalAmount(
                                        provisionalAmount, settlementFxRate, finalRate))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_CALCULATION_FAILED)
                .hasFieldOrPropertyWithValue("errorData", fieldName + " - 요청 값 오류");
    }

    @ParameterizedTest(name = "{0}={1}이면 계산을 차단한다")
    @MethodSource("nonPositiveInputs")
    @DisplayName("계산 입력값이 0 이하이면 SettlementCalculationException을 반환한다")
    void calculateFinalAmountRejectsNonPositiveInput(
            String fieldName,
            BigDecimal invalidValue,
            BigDecimal provisionalAmount,
            BigDecimal settlementFxRate,
            BigDecimal finalRate) {
        assertThatThrownBy(
                        () ->
                                calculator.calculateFinalAmount(
                                        provisionalAmount, settlementFxRate, finalRate))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_CALCULATION_FAILED)
                .hasFieldOrPropertyWithValue("errorData", fieldName + "은 0보다 커야 합니다.");
    }

    private static Stream<Arguments> exchangeRateScenarios() {
        return Stream.of(
                Arguments.of("확정환율 상승", "2700000.00", "1350.0000", "1400.000000", "2828283"),
                Arguments.of("확정환율 하락", "2700000.00", "1350.0000", "1300.000000", "2626263"),
                Arguments.of("확정환율 동일", "2700000.00", "1350.0000", "1350.000000", "2727273"));
    }

    private static Stream<Arguments> nullInputs() {
        return Stream.of(
                Arguments.of("provisionalAmount", null, BigDecimal.ONE, BigDecimal.ONE),
                Arguments.of("settlementFxRate", BigDecimal.ONE, null, BigDecimal.ONE),
                Arguments.of("finalRate", BigDecimal.ONE, BigDecimal.ONE, null));
    }

    private static Stream<Arguments> nonPositiveInputs() {
        return Stream.of(
                invalidInput("provisionalAmount", BigDecimal.ZERO),
                invalidInput("provisionalAmount", BigDecimal.ONE.negate()),
                invalidInput("settlementFxRate", BigDecimal.ZERO),
                invalidInput("settlementFxRate", BigDecimal.ONE.negate()),
                invalidInput("finalRate", BigDecimal.ZERO),
                invalidInput("finalRate", BigDecimal.ONE.negate()));
    }

    private static Arguments invalidInput(String fieldName, BigDecimal invalidValue) {
        BigDecimal provisionalAmount = BigDecimal.ONE;
        BigDecimal settlementFxRate = BigDecimal.ONE;
        BigDecimal finalRate = BigDecimal.ONE;

        if ("provisionalAmount".equals(fieldName)) {
            provisionalAmount = invalidValue;
        } else if ("settlementFxRate".equals(fieldName)) {
            settlementFxRate = invalidValue;
        } else {
            finalRate = invalidValue;
        }

        return Arguments.of(
                fieldName, invalidValue, provisionalAmount, settlementFxRate, finalRate);
    }
}
