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
public class FundFlowRetentionDTO {

    private LocalDate asOfDate;

    /** 확정된 환전 건 수 */
    private Long lots;

    private Long accounts;

    /** 1년 미경과 원금 인출이 일어난 건 수 */
    private Long events;

    /** 가장 오래 관찰된 경과일 */
    private Integer maxObservedDays;

    private Integer bootstrapReplicates;
    private List<FundFlowRetentionPointDTO> points;
}
