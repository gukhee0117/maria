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
public class FundFlowSegmentDTO {
    private LocalDate asOfDate;
    private List<FundFlowSegmentCellDTO> cells;
    private List<FundFlowAgeMedianDTO> ageMedians;
}
