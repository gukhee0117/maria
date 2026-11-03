package com.app.maria.domain.fundflow.dto.request;

import com.app.maria.domain.fundflow.type.ReliefScope;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowReliefRequestDTO {

    /** 생략하면 ALL. */
    private ReliefScope scope = ReliefScope.ALL;
}
