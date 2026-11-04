package com.app.maria.domain.fundflow.dto;

import com.app.maria.domain.fundflow.type.FundFlowTrendUnit;
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
public class FundFlowTrendDTO {
    private LocalDate asOfDate;
    private boolean hasAccount;

    /** 원화 확정이 한 번이라도 있었는지. 없으면 points 는 비어 있다. */
    private boolean hasKrwConfirmed;

    private FundFlowTrendUnit unit;

    /** 오래된 순. */
    private List<FundFlowTrendPointDTO> points;
}
