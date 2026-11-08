package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowConcentrationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLorenzPointDTO;
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
public class FundFlowConcentrationResponseDTO {

    private static final String BASIS =
            "감면액이 0보다 큰 계좌만 대상. 감면액 = 일반 과세 세액 − 최종 세액(세액 스냅샷 기준 예상값). 지니계수 0은 완전 균등, 1은 한 계좌 독점";

    private LocalDate asOfDate;
    private Long accounts;
    private BigDecimal reliefTotal;
    private BigDecimal top10Share;
    private BigDecimal gini;
    private List<Point> lorenz;
    private String basis;

    public FundFlowConcentrationResponseDTO(FundFlowConcentrationDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.accounts = vo.getAccounts();
        this.reliefTotal = vo.getReliefTotal();
        this.top10Share = vo.getTop10Share();
        this.gini = vo.getGini();
        this.lorenz = vo.getLorenz().stream().map(Point::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Point {
        private BigDecimal populationShare;
        private BigDecimal valueShare;

        public Point(FundFlowLorenzPointDTO vo) {
            this.populationShare = vo.getPopulationShare();
            this.valueShare = vo.getValueShare();
        }
    }
}
