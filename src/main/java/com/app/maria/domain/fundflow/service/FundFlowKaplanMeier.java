package com.app.maria.domain.fundflow.service;

import java.util.Arrays;
import java.util.Random;

/**
 * 원금 유지 곡선(Kaplan-Meier). 단위는 확정된 환전 건이고 같은 계좌의 건은 사건 시점을 공유하므로, 신뢰구간은 계좌 단위 부트스트랩(백분위 방식)으로 구한다.
 * 시드를 고정해 같은 입력이면 같은 결과가 나온다.
 */
final class FundFlowKaplanMeier {

    static final int REPLICATES = 200;
    static final long SEED = 20261118L;
    private static final double LOWER_Q = 0.025;
    private static final double UPPER_Q = 0.975;

    private FundFlowKaplanMeier() {}

    /** 건 단위 입력. accountIdx 기준으로 같은 계좌가 연속해 있어야 한다. days는 0 이상. */
    record Input(int[] accountIdx, int[] days, boolean[] events, double[] weights) {
        int size() {
            return days.length;
        }
    }

    record Result(
            int[] grid,
            double[] survival,
            double[] lower,
            double[] upper,
            double[] weightedSurvival,
            int[] atRisk) {}

    static Result compute(Input in, int step) {
        int n = in.size();
        int maxDay = 0;
        for (int d : in.days()) {
            maxDay = Math.max(maxDay, d);
        }
        int[] grid = buildGrid(maxDay, step);
        if (n == 0) {
            double[] ones = new double[grid.length];
            Arrays.fill(ones, 1.0);
            return new Result(
                    grid, ones, ones.clone(), ones.clone(), ones.clone(), new int[grid.length]);
        }

        double[] unit = new double[n];
        Arrays.fill(unit, 1.0);
        double[] survival = survivalAt(in.days(), in.events(), unit, grid, maxDay);
        double[] weighted = survivalAt(in.days(), in.events(), in.weights(), grid, maxDay);

        int[] atRisk = new int[grid.length];
        for (int d : in.days()) {
            for (int g = 0; g < grid.length && grid[g] <= d; g++) {
                atRisk[g]++;
            }
        }

        double[][] boot = bootstrap(in, grid, maxDay);
        double[] lower = new double[grid.length];
        double[] upper = new double[grid.length];
        for (int g = 0; g < grid.length; g++) {
            double[] column = new double[boot.length];
            for (int r = 0; r < boot.length; r++) {
                column[r] = boot[r][g];
            }
            Arrays.sort(column);
            lower[g] = percentile(column, LOWER_Q);
            upper[g] = percentile(column, UPPER_Q);
        }
        return new Result(grid, survival, lower, upper, weighted, atRisk);
    }

    static int[] buildGrid(int maxDay, int step) {
        int count = maxDay / step + 1;
        boolean needsLast = maxDay % step != 0;
        int[] grid = new int[count + (needsLast ? 1 : 0)];
        for (int i = 0; i < count; i++) {
            grid[i] = i * step;
        }
        if (needsLast) {
            grid[count] = maxDay;
        }
        return grid;
    }

    private static double[] survivalAt(
            int[] days, boolean[] events, double[] weights, int[] grid, int maxDay) {
        double[] total = new double[maxDay + 2];
        double[] event = new double[maxDay + 2];
        for (int i = 0; i < days.length; i++) {
            total[days[i]] += weights[i];
            if (events[i]) {
                event[days[i]] += weights[i];
            }
        }
        return curve(total, event, grid, maxDay);
    }

    private static double[][] bootstrap(Input in, int[] grid, int maxDay) {
        int n = in.size();
        int accounts = 0;
        int[] starts = new int[n + 1];
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (in.accountIdx()[i] != prev) {
                starts[accounts++] = i;
                prev = in.accountIdx()[i];
            }
        }
        starts[accounts] = n;

        Random random = new Random(SEED);
        double[][] result = new double[REPLICATES][];
        for (int r = 0; r < REPLICATES; r++) {
            double[] total = new double[maxDay + 2];
            double[] event = new double[maxDay + 2];
            for (int draw = 0; draw < accounts; draw++) {
                int a = random.nextInt(accounts);
                for (int i = starts[a]; i < starts[a + 1]; i++) {
                    total[in.days()[i]] += 1.0;
                    if (in.events()[i]) {
                        event[in.days()[i]] += 1.0;
                    }
                }
            }
            result[r] = curve(total, event, grid, maxDay);
        }
        return result;
    }

    /** total[d], event[d] = 기간이 d일인 건의 (가중)합계와 그중 사건 합계. 위험집합은 d일 이상인 건. */
    private static double[] curve(double[] total, double[] event, int[] grid, int maxDay) {
        double[] atRisk = new double[maxDay + 2];
        for (int d = maxDay; d >= 0; d--) {
            atRisk[d] = atRisk[d + 1] + total[d];
        }
        double[] out = new double[grid.length];
        double s = 1.0;
        int g = 0;
        for (int d = 0; d <= maxDay && g < grid.length; d++) {
            if (atRisk[d] > 0) {
                s *= 1.0 - event[d] / atRisk[d];
            }
            while (g < grid.length && grid[g] == d) {
                out[g++] = s;
            }
        }
        return out;
    }

    private static double percentile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int lo = (int) Math.floor(position);
        int hi = (int) Math.ceil(position);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (position - lo);
    }
}
