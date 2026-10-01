package com.app.maria.domain.tax.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaxActionSummaryResponseDTO {
    private int unconfirmedFinalReportCount;
    private int unprocessedClawbackCount;
    private int benefitChangedTodayCount;
}
