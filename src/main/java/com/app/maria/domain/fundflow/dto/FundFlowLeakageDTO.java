package com.app.maria.domain.fundflow.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class FundFlowLeakageDTO {
    private LocalDate asOfDate;
    private LocalDateTime snapshotCalculatedAt;
    private Long total;
    private List<FundFlowLeakageCategoryDTO> categories;
    private List<FundFlowRatioBinDTO> ratioBins;
}
