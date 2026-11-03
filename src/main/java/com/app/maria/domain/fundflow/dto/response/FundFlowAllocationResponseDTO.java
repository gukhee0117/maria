package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowAllocationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowBucketDTO;
import com.app.maria.domain.fundflow.dto.FundFlowNetBuyDTO;
import java.math.BigDecimal;
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
public class FundFlowAllocationResponseDTO {

    private static final String VALUE_BASIS = "PURCHASE_COST";
    private static final String BASIS =
            "매입금액 기준(보유수량 × 평균 매입가). 현재가 평가액이 아님. HHI = Σ점유율², 0~1 (1에 가까울수록 쏠림)";

    private LocalDate asOfDate;
    private String valueBasis;
    private BigDecimal totalValue;
    private List<Bucket> buckets;
    private BigDecimal hhiBucket;
    private BigDecimal hhiProduct;
    private List<NetBuy> topNetBuy;
    private String basis;

    public FundFlowAllocationResponseDTO(FundFlowAllocationDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.valueBasis = VALUE_BASIS;
        this.totalValue = vo.getTotalCost();
        this.buckets = vo.getBuckets().stream().map(Bucket::new).toList();
        this.hhiBucket = vo.getHhiBucket();
        this.hhiProduct = vo.getHhiProduct();
        this.topNetBuy = vo.getTopNetBuy().stream().map(NetBuy::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Bucket {
        private String label;
        private BigDecimal value;
        private BigDecimal share;

        public Bucket(FundFlowBucketDTO vo) {
            this.label = vo.getLabel();
            this.value = vo.getPurchaseCost();
            this.share = vo.getShare();
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class NetBuy {
        private String name;
        private String type;
        private String sectorName;
        private BigDecimal netBuyExec;

        public NetBuy(FundFlowNetBuyDTO vo) {
            this.name = vo.getName();
            this.type = vo.getType();
            this.sectorName = vo.getSectorName();
            this.netBuyExec = vo.getNetBuyExec();
        }
    }
}
