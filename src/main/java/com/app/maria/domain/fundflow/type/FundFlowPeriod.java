package com.app.maria.domain.fundflow.type;

import java.time.LocalDate;

/** 자금 환류 대시보드의 조회 기간. 기준일(asOfDate)이 속한 달·분기의 첫날부터 기준일까지 본다. ALL은 하한 없이 출시 이후 누적이다. */
public enum FundFlowPeriod {
    ALL,
    MONTH,
    QUARTER;

    /** 기간의 첫날(포함). 하한이 없으면 null. */
    public LocalDate startOf(LocalDate asOfDate) {
        return switch (this) {
            case ALL -> null;
            case MONTH -> asOfDate.withDayOfMonth(1);
            case QUARTER ->
                    asOfDate.withDayOfMonth(1)
                            .withMonth((asOfDate.getMonthValue() - 1) / 3 * 3 + 1);
        };
    }
}
