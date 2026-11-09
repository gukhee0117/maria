package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundFlowStatisticsTest {

    private static List<BigDecimal> values(String... numbers) {
        return java.util.Arrays.stream(numbers).map(BigDecimal::new).toList();
    }

    @Test
    @DisplayName("중앙값: 홀수 개는 가운데 값, 짝수 개는 가운데 두 값의 평균, 비어 있으면 null")
    void medianHandlesOddEvenAndEmpty() {
        assertThat(FundFlowStatistics.median(values("5", "1", "3"))).isEqualByComparingTo("3");
        assertThat(FundFlowStatistics.median(values("4", "1", "2", "3")))
                .isEqualByComparingTo("2.5");
        assertThat(FundFlowStatistics.median(List.of())).isNull();
    }

    @Test
    @DisplayName("중앙값: 극단값이 있어도 평균처럼 끌려가지 않고, 입력 목록은 바꾸지 않는다")
    void medianIgnoresOutliersAndKeepsInputOrder() {
        List<BigDecimal> input = values("100", "1", "2", "3", "4");

        assertThat(FundFlowStatistics.median(input)).isEqualByComparingTo("3");
        assertThat(input).containsExactlyElementsOf(values("100", "1", "2", "3", "4"));
    }

    @Test
    @DisplayName("지니계수: 모두 같으면 0, (1, 3)이면 0.25")
    void giniOfEqualValuesIsZeroAndKnownCase() {
        assertThat(FundFlowStatistics.gini(values("2", "2", "2", "2"))).isEqualByComparingTo("0");
        assertThat(FundFlowStatistics.gini(values("1", "3"))).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("지니계수: 값이 없거나 합이 0이면 null")
    void giniIsNullWithoutValues() {
        assertThat(FundFlowStatistics.gini(List.of())).isNull();
        assertThat(FundFlowStatistics.gini(values("0", "0"))).isNull();
    }

    @Test
    @DisplayName("상위 점유율: 상위 50%는 올림한 인원의 합 ÷ 전체")
    void topShareUsesCeilingOfPopulation() {
        assertThat(FundFlowStatistics.topShare(values("1", "2", "3", "4"), new BigDecimal("0.5")))
                .isEqualByComparingTo("0.7");
        assertThat(FundFlowStatistics.topShare(values("1", "1", "1"), new BigDecimal("0.10")))
                .isEqualByComparingTo("0.3333");
    }

    @Test
    @DisplayName("로렌츠: 균등하면 대각선, 하위 구간은 누적 비율")
    void lorenzIsDiagonalWhenEqual() {
        List<BigDecimal[]> points = FundFlowStatistics.lorenz(values("1", "1", "1", "1"), 25);

        assertThat(points).hasSize(5);
        for (BigDecimal[] point : points) {
            assertThat(point[1]).isEqualByComparingTo(point[0]);
        }
    }

    @Test
    @DisplayName("로렌츠: 한쪽으로 쏠리면 하위 구간의 누적 비율이 작다")
    void lorenzShowsConcentration() {
        List<BigDecimal[]> points = FundFlowStatistics.lorenz(values("1", "1", "1", "97"), 25);

        assertThat(points.get(3)[0]).isEqualByComparingTo("0.75");
        assertThat(points.get(3)[1]).isEqualByComparingTo("0.03");
        assertThat(points.get(4)[1]).isEqualByComparingTo("1");
    }
}
