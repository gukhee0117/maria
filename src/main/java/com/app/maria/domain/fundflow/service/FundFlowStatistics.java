package com.app.maria.domain.fundflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 분포 요약 통계: 중앙값, 지니계수, 로렌츠 곡선, 상위 점유율. 금액은 BigDecimal로만 계산한다. */
final class FundFlowStatistics {

    private static final int SCALE = 4;
    private static final int INTERNAL_SCALE = 12;

    private FundFlowStatistics() {}

    /** 값이 없으면 null. 짝수 개면 가운데 두 값의 평균(소수 4자리). 입력 순서는 건드리지 않는다. */
    static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return sorted.get(n / 2 - 1)
                .add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total;
    }

    /** G = 2·Σ(i·xᵢ) / (n·Σx) − (n+1)/n, xᵢ는 오름차순. 값이 없거나 합이 0이면 null. */
    static BigDecimal gini(List<BigDecimal> ascending) {
        int n = ascending == null ? 0 : ascending.size();
        BigDecimal total = n == 0 ? BigDecimal.ZERO : sum(ascending);
        if (total.signum() == 0) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            weighted = weighted.add(ascending.get(i).multiply(BigDecimal.valueOf(i + 1L)));
        }
        BigDecimal count = BigDecimal.valueOf(n);
        BigDecimal first =
                weighted.multiply(BigDecimal.valueOf(2))
                        .divide(count.multiply(total), INTERNAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal second =
                count.add(BigDecimal.ONE).divide(count, INTERNAL_SCALE, RoundingMode.HALF_UP);
        return first.subtract(second).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 상위 share 비율(올림한 인원)의 합이 전체에서 차지하는 비율. 합이 0이면 null. */
    static BigDecimal topShare(List<BigDecimal> ascending, BigDecimal share) {
        int n = ascending == null ? 0 : ascending.size();
        BigDecimal total = n == 0 ? BigDecimal.ZERO : sum(ascending);
        if (total.signum() == 0) {
            return null;
        }
        int topCount =
                BigDecimal.valueOf(n).multiply(share).setScale(0, RoundingMode.CEILING).intValue();
        BigDecimal top = BigDecimal.ZERO;
        for (int i = n - topCount; i < n; i++) {
            top = top.add(ascending.get(i));
        }
        return top.divide(total, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 로렌츠 곡선: 하위 p%(p = 0, step, 2·step … 100)가 가진 합계 비율. 반환 배열의 [0]은 인구 비율, [1]은 값 비율. 합이 0이면 빈
     * 목록.
     */
    static List<BigDecimal[]> lorenz(List<BigDecimal> ascending, int stepPercent) {
        int n = ascending == null ? 0 : ascending.size();
        BigDecimal total = n == 0 ? BigDecimal.ZERO : sum(ascending);
        List<BigDecimal[]> points = new ArrayList<>();
        if (total.signum() == 0) {
            return points;
        }
        BigDecimal[] prefix = new BigDecimal[n + 1];
        prefix[0] = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i].add(ascending.get(i));
        }
        for (int percent = 0; percent <= 100; percent += stepPercent) {
            int upto =
                    BigDecimal.valueOf(n)
                            .multiply(BigDecimal.valueOf(percent))
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                            .intValue();
            points.add(
                    new BigDecimal[] {
                        BigDecimal.valueOf(percent)
                                .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP),
                        prefix[upto].divide(total, SCALE, RoundingMode.HALF_UP)
                    });
        }
        return points;
    }
}
