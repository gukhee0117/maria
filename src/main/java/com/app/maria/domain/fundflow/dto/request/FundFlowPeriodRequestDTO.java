package com.app.maria.domain.fundflow.dto.request;

import com.app.maria.domain.fundflow.type.FundFlowPeriod;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowPeriodRequestDTO {

    /** 생략하면 ALL(출시 이후 누적). */
    private FundFlowPeriod period = FundFlowPeriod.ALL;
}
