package com.app.maria.domain.fundflow.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowFilterDTO {

    /** 응답 기준일 = 업무시계 날짜 - 1일 (전일까지 집계) */
    private LocalDate asOfDate;

    /** 업무시계 날짜. 일자 조건은 {@code < clockDate} 로 건다. */
    private LocalDate clockDate;

    /** 업무시계가 속한 달의 1일. 이 날짜 이전 달은 fund_flow_monthly, 이 달은 원천 집계(진행 중). */
    private LocalDate monthStart;

    /** 월별 조회 하한(월 1일, 포함). null이면 제한 없음. */
    private LocalDate fromMonth;

    /** 월별 조회 상한(월 1일, 포함). null이면 제한 없음. */
    private LocalDate toMonth;

    /** 조회 기간의 첫날(포함). null이면 하한 없음(출시 이후 누적). 상한은 항상 {@code < clockDate}. */
    private LocalDate periodFrom;

    /** 감면액 분모 범위(ALL/POSITIVE). */
    private String scope;

    public static FundFlowFilterDTO from(LocalDateTime clockNow) {
        LocalDate clockDate = clockNow.toLocalDate();
        return FundFlowFilterDTO.builder()
                .asOfDate(clockDate.minusDays(1))
                .clockDate(clockDate)
                .monthStart(clockDate.withDayOfMonth(1))
                .build();
    }
}
