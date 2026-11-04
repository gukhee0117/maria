package com.app.maria.domain.stockpackage.type;

import com.app.maria.domain.customer.entity.InvestorType;
import java.util.Arrays;
import java.util.Optional;

/**
 * 투자성향별로 담을 수 있는 위험등급 범위. 등급은 1(매우 높은 위험)~6(매우 낮은 위험)이고, 성향마다 허용하는 가장 위험한 등급(minGrade)이 정해져 있다. 등급
 * 숫자가 minGrade 이상이면 성향 범위 안이다.
 */
public enum InvestorRiskRange {
    AGGRESSIVE(InvestorType.AGGRESSIVE, 1, "공격투자형"),
    ACTIVE(InvestorType.ACTIVE, 2, "적극투자형"),
    NEUTRAL(InvestorType.NEUTRAL, 4, "위험중립형"),
    CONSERVATIVE(InvestorType.CONSERVATIVE, 5, "안정추구형"),
    STABLE(InvestorType.STABLE, 6, "안정형");

    private final InvestorType investorType;
    private final int minGrade;
    private final String label;

    InvestorRiskRange(InvestorType investorType, int minGrade, String label) {
        this.investorType = investorType;
        this.minGrade = minGrade;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public int minGrade() {
        return minGrade;
    }

    public boolean allows(int riskGrade) {
        return riskGrade >= minGrade;
    }

    /** 진단하지 않은 성향(UNASSIGNED)은 범위를 알 수 없어 비어 있다. */
    public static Optional<InvestorRiskRange> of(InvestorType investorType) {
        return Arrays.stream(values()).filter(r -> r.investorType == investorType).findFirst();
    }
}
