package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowLeakageCategoryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRatioBinDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class FundFlowLeakageResponseDTO {

    private static final String BASIS = "tax_snapshot은 표시용 캐시로, 예상값이며 확정 세액이 아님";

    private LocalDate asOfDate;
    private LocalDateTime snapshotCalculatedAt;
    private Long total;
    private List<Category> categories;
    private List<RatioBin> ratioBins;
    private String basis;

    public FundFlowLeakageResponseDTO(FundFlowLeakageDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.snapshotCalculatedAt = vo.getSnapshotCalculatedAt();
        this.total = vo.getTotal();
        this.categories = vo.getCategories().stream().map(Category::new).toList();
        this.ratioBins = vo.getRatioBins().stream().map(RatioBin::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class RatioBin {
        private String code;
        private String label;
        private Long accounts;
        private BigDecimal share;

        public RatioBin(FundFlowRatioBinDTO vo) {
            this.code = vo.getCode();
            this.label = vo.getLabel();
            this.accounts = vo.getAccounts();
            this.share = vo.getShare();
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Category {
        private String code;
        private String label;
        private Long accounts;
        private BigDecimal share;
        private Long withExternalBuy;

        public Category(FundFlowLeakageCategoryDTO vo) {
            this.code = vo.getCode();
            this.label = vo.getLabel();
            this.accounts = vo.getAccounts();
            this.share = vo.getShare();
            this.withExternalBuy = vo.getWithExternalBuy();
        }
    }
}
