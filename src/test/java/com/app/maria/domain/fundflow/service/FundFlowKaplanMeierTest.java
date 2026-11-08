package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundFlowKaplanMeierTest {

    private static FundFlowKaplanMeier.Input input(int[] accounts, int[] days, boolean[] events) {
        double[] weights = new double[days.length];
        java.util.Arrays.fill(weights, 1.0);
        return new FundFlowKaplanMeier.Input(accounts, days, events, weights);
    }

    @Test
    @DisplayName("손으로 계산한 값과 같다: 5일 사건 1건, 10일 사건 1건·절단 1건, 20일 절단 1건")
    void matchesHandComputedCurve() {
        var result =
                FundFlowKaplanMeier.compute(
                        input(
                                new int[] {1, 2, 3, 4},
                                new int[] {5, 10, 10, 20},
                                new boolean[] {true, false, true, false}),
                        10);

        assertThat(result.grid()).containsExactly(0, 10, 20);
        assertThat(result.survival()[0]).isCloseTo(1.0, within(1e-12));
        // 5일: 위험집합 4 중 사건 1 → 0.75, 10일: 위험집합 3 중 사건 1 → 0.75 × 2/3 = 0.5
        assertThat(result.survival()[1]).isCloseTo(0.5, within(1e-12));
        assertThat(result.survival()[2]).isCloseTo(0.5, within(1e-12));
        assertThat(result.atRisk()).containsExactly(4, 3, 1);
    }

    @Test
    @DisplayName("금액 가중 곡선은 금액이 큰 건의 사건을 더 크게 반영한다")
    void weightedCurveFollowsAmounts() {
        var result =
                FundFlowKaplanMeier.compute(
                        new FundFlowKaplanMeier.Input(
                                new int[] {1, 2},
                                new int[] {5, 20},
                                new boolean[] {true, false},
                                new double[] {1.0, 9.0}),
                        10);

        // 건수 기준 0.5, 금액 기준 1 - 1/10 = 0.9
        assertThat(result.survival()[1]).isCloseTo(0.5, within(1e-12));
        assertThat(result.weightedSurvival()[1]).isCloseTo(0.9, within(1e-12));
    }

    @Test
    @DisplayName("신뢰구간은 0~1 안에서 하한 ≤ 상한이고 같은 입력이면 같은 값이다")
    void intervalIsOrderedAndReproducible() {
        int n = 60;
        int[] accounts = new int[n];
        int[] days = new int[n];
        boolean[] events = new boolean[n];
        for (int i = 0; i < n; i++) {
            accounts[i] = i / 2;
            days[i] = 5 + (i % 10) * 7;
            events[i] = i % 6 == 0;
        }

        var first = FundFlowKaplanMeier.compute(input(accounts, days, events), 10);
        var second = FundFlowKaplanMeier.compute(input(accounts, days, events), 10);

        for (int g = 0; g < first.grid().length; g++) {
            assertThat(first.lower()[g]).isBetween(0.0, 1.0).isLessThanOrEqualTo(first.upper()[g]);
            assertThat(first.upper()[g]).isBetween(0.0, 1.0);
        }
        assertThat(first.lower()).containsExactly(second.lower());
        assertThat(first.upper()).containsExactly(second.upper());
    }

    @Test
    @DisplayName("사건이 없으면 곡선은 1이고 건이 없으면 격자만 낸다")
    void noEventsKeepsCurveAtOne() {
        var none =
                FundFlowKaplanMeier.compute(
                        input(new int[] {1, 2}, new int[] {30, 40}, new boolean[] {false, false}),
                        10);
        var empty = FundFlowKaplanMeier.compute(input(new int[0], new int[0], new boolean[0]), 10);

        for (double s : none.survival()) {
            assertThat(s).isEqualTo(1.0);
        }
        assertThat(empty.grid()).containsExactly(0);
        assertThat(empty.survival()).containsExactly(1.0);
    }

    @Test
    @DisplayName("격자는 간격마다 찍고 마지막 관찰일이 간격에 안 맞으면 끝에 덧붙인다")
    void gridIncludesLastObservedDay() {
        assertThat(FundFlowKaplanMeier.buildGrid(236, 10)).endsWith(230, 236);
        assertThat(FundFlowKaplanMeier.buildGrid(240, 10)).endsWith(230, 240);
        assertThat(FundFlowKaplanMeier.buildGrid(0, 10)).containsExactly(0);
    }
}
