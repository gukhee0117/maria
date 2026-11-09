package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
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
public class FundFlowRetentionPointDTO {

    /** 확정 후 경과일 */
    private Integer day;

    /** 건수 기준 원금 유지 확률 (0~1) */
    private BigDecimal survival;

    /** 95% 신뢰구간 하한 (계좌 단위 부트스트랩) */
    private BigDecimal lower;

    private BigDecimal upper;

    /** 금액 가중 원금 유지 확률. 신뢰구간 없음 */
    private BigDecimal weightedSurvival;

    /** 이 경과일 이상을 관찰한 건 수(위험집합) */
    private Long atRisk;
}
