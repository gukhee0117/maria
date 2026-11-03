package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowBucketDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerAllocationDTO;
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
public class FundFlowCustomerAllocationResponseDTO {

    private static final String VALUE_BASIS = "PURCHASE_COST";
    private static final String BASIS = "매입금액 기준(보유수량 × 평균 매입가). 현재가 평가액이 아니에요";

    private LocalDate asOfDate;
    private boolean hasAccount;
    private String valueBasis;
    private BigDecimal holdingsCost;
    private BigDecimal cashBalance;
    private BigDecimal totalValue;
    private List<Bucket> buckets;
    private List<Bucket> sectors;
    private String basis;

    public FundFlowCustomerAllocationResponseDTO(FundFlowCustomerAllocationDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.hasAccount = vo.isHasAccount();
        this.valueBasis = VALUE_BASIS;
        this.holdingsCost = vo.getHoldingsCost();
        this.cashBalance = vo.getCashBalance();
        this.totalValue = vo.getTotalCost();
        this.buckets =
                vo.getBuckets() == null
                        ? List.of()
                        : vo.getBuckets().stream().map(Bucket::new).toList();
        this.sectors =
                vo.getSectors() == null
                        ? List.of()
                        : vo.getSectors().stream().map(Bucket::new).toList();
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
}
