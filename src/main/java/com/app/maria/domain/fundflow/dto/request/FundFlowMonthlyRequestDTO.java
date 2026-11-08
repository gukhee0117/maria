package com.app.maria.domain.fundflow.dto.request;

import com.app.maria.domain.fundflow.type.FundFlowPeriod;
import java.time.YearMonth;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowMonthlyRequestDTO {

    @DateTimeFormat(pattern = "yyyy-MM")
    private YearMonth fromMonth;

    @DateTimeFormat(pattern = "yyyy-MM")
    private YearMonth toMonth;

    /** fromMonth를 따로 주지 않았을 때 하한을 정하는 기간. 생략하면 ALL. */
    private FundFlowPeriod period = FundFlowPeriod.ALL;
}
