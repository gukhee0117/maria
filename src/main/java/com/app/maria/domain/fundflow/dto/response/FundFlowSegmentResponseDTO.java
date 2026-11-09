package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowAgeMedianDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentCellDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentDTO;
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
public class FundFlowSegmentResponseDTO {

    private static final String BASIS = "연령은 기준일 만 나이. 5개 미만 칸은 합계·비율이 null";

    private LocalDate asOfDate;
    private List<Cell> cells;
    private List<AgeMedian> ageMedians;
    private String basis;

    public FundFlowSegmentResponseDTO(FundFlowSegmentDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.cells = vo.getCells().stream().map(Cell::new).toList();
        this.ageMedians = vo.getAgeMedians().stream().map(AgeMedian::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class AgeMedian {
        private String ageBand;
        private Long accounts;
        private Long accountsWithInflow;
        private BigDecimal inflowMedian;
        private BigDecimal reinvestMedian;

        public AgeMedian(FundFlowAgeMedianDTO vo) {
            this.ageBand = vo.getAgeBand();
            this.accounts = vo.getAccounts();
            this.accountsWithInflow = vo.getAccountsWithInflow();
            this.inflowMedian = vo.getInflowMedian();
            this.reinvestMedian = vo.getReinvestMedian();
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Cell {
        private String ageBand;
        private String investorType;
        private Long accounts;
        private Long accountsWithInflow;
        private BigDecimal inflowTotal;
        private BigDecimal buyTotal;
        private BigDecimal reinvestRatio;
        private BigDecimal netInvestRatio;

        public Cell(FundFlowSegmentCellDTO vo) {
            this.ageBand = vo.getAgeBand();
            this.investorType = vo.getInvestorType();
            this.accounts = vo.getAccounts();
            this.accountsWithInflow = vo.getAccountsWithInflow();
            this.inflowTotal = vo.getInflowTotal();
            this.buyTotal = vo.getBuyTotal();
            this.reinvestRatio = vo.getReinvestRatio();
            this.netInvestRatio = vo.getNetInvestRatio();
        }
    }
}
