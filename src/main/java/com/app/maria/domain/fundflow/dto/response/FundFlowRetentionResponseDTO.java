package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowRetentionDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRetentionPointDTO;
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
public class FundFlowRetentionResponseDTO {

    private static final String BASIS =
            "단위는 확정된 환전 건. 시작 = 환전 확정 시각, 사건 = 그 건의 1년 미경과 원금 인출, 사건이 없으면 기준일에서 중도절단."
                    + " 신뢰구간은 계좌 단위 부트스트랩(백분위 95%)";

    private LocalDate asOfDate;
    private Long lots;
    private Long accounts;
    private Long events;
    private Integer maxObservedDays;
    private Integer bootstrapReplicates;
    private List<Point> points;
    private String basis;

    public FundFlowRetentionResponseDTO(FundFlowRetentionDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.lots = vo.getLots();
        this.accounts = vo.getAccounts();
        this.events = vo.getEvents();
        this.maxObservedDays = vo.getMaxObservedDays();
        this.bootstrapReplicates = vo.getBootstrapReplicates();
        this.points = vo.getPoints().stream().map(Point::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Point {
        private Integer day;
        private BigDecimal survival;
        private BigDecimal lower;
        private BigDecimal upper;
        private BigDecimal weightedSurvival;
        private Long atRisk;

        public Point(FundFlowRetentionPointDTO vo) {
            this.day = vo.getDay();
            this.survival = vo.getSurvival();
            this.lower = vo.getLower();
            this.upper = vo.getUpper();
            this.weightedSurvival = vo.getWeightedSurvival();
            this.atRisk = vo.getAtRisk();
        }
    }
}
