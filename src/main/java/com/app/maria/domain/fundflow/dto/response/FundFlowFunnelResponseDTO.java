package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowFunnelDTO;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowFunnelResponseDTO {

    private static final String BASIS = "고객당 처음 도달 기준, 앞 단계를 모두 지난 고객만 집계";

    private LocalDate asOfDate;
    private List<FundFlowFunnelStageResponseDTO> stages;
    private String basis;

    public FundFlowFunnelResponseDTO(FundFlowFunnelDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.stages = vo.getStages().stream().map(FundFlowFunnelStageResponseDTO::new).toList();
        this.basis = BASIS;
    }
}
