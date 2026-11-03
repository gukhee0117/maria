package com.app.maria.domain.fundflow.dto.request;

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
}
