package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowTrendDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTrendPointDTO;
import com.app.maria.domain.fundflow.type.FundFlowTrendUnit;
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
public class FundFlowTrendResponseDTO {

    private static final String BASIS =
            "기간 말(주는 일요일, 월은 말일) 잔액. invested는 보유 종목 매입금액(보유수량 × 이동평균 매입가, 수수료 제외)이라 /summary 의 국내 보유 매입금액과 같은 기준이에요. cash는 예수금";

    private LocalDate asOfDate;
    private boolean hasAccount;
    private boolean hasKrwConfirmed;
    private FundFlowTrendUnit unit;
    private List<Point> points;
    private String basis;

    public FundFlowTrendResponseDTO(FundFlowTrendDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.hasAccount = vo.isHasAccount();
        this.hasKrwConfirmed = vo.isHasKrwConfirmed();
        this.unit = vo.getUnit();
        this.points = vo.getPoints().stream().map(Point::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Point {
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private BigDecimal invested;
        private BigDecimal cash;
        private boolean hasTransactions;

        public Point(FundFlowTrendPointDTO vo) {
            this.periodStart = vo.getPeriodStart();
            this.periodEnd = vo.getPeriodEnd();
            this.invested = vo.getInvested();
            this.cash = vo.getCash();
            this.hasTransactions = vo.isHasTransactions();
        }
    }
}
