package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 확정된 환전 한 건. days는 확정 후 사건(1년 미경과 원금 인출)까지 또는 기준일까지의 경과일. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowRetentionLotRowDTO {

    private Long accountId;
    private BigDecimal finalAmount;
    private Integer days;
    private Boolean event;
}
