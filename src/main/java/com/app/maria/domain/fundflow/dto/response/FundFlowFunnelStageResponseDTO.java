package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowFunnelStageDTO;
import java.math.BigDecimal;
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
public class FundFlowFunnelStageResponseDTO {
    private Integer stageNo;
    private String stage;
    private Long customers;
    private BigDecimal convPrev;
    private BigDecimal convTotal;

    public FundFlowFunnelStageResponseDTO(FundFlowFunnelStageDTO vo) {
        this.stageNo = vo.getStageNo();
        this.stage = vo.getStage();
        this.customers = vo.getCustomers();
        this.convPrev = vo.getConvPrev();
        this.convTotal = vo.getConvTotal();
    }
}
