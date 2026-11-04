package com.app.maria.domain.fundflow.dto;

import java.time.LocalDate;
import java.util.List;
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
public class FundFlowWithdrawalScheduleDTO {
    /** 업무시계의 오늘. 일정 막대의 "오늘" 선과 인출 가능 판정이 이 날짜를 쓴다. */
    private LocalDate businessDate;

    private boolean hasAccount;

    /** 납입일 오름차순. */
    private List<FundFlowWithdrawalLotDTO> lots;
}
