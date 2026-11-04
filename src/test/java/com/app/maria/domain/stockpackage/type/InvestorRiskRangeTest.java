package com.app.maria.domain.stockpackage.type;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.customer.entity.InvestorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvestorRiskRangeTest {

    @Test
    @DisplayName("성향별로 허용하는 가장 위험한 등급이 정해져 있다")
    void minGradeByType() {
        assertThat(InvestorRiskRange.of(InvestorType.AGGRESSIVE).orElseThrow().minGrade())
                .isEqualTo(1);
        assertThat(InvestorRiskRange.of(InvestorType.ACTIVE).orElseThrow().minGrade()).isEqualTo(2);
        assertThat(InvestorRiskRange.of(InvestorType.NEUTRAL).orElseThrow().minGrade())
                .isEqualTo(4);
        assertThat(InvestorRiskRange.of(InvestorType.CONSERVATIVE).orElseThrow().minGrade())
                .isEqualTo(5);
        assertThat(InvestorRiskRange.of(InvestorType.STABLE).orElseThrow().minGrade()).isEqualTo(6);
    }

    @Test
    @DisplayName("등급 숫자가 minGrade 이상이면 성향 범위 안이다")
    void allows() {
        InvestorRiskRange neutral = InvestorRiskRange.of(InvestorType.NEUTRAL).orElseThrow();
        assertThat(neutral.allows(3)).isFalse();
        assertThat(neutral.allows(4)).isTrue();
        assertThat(neutral.allows(6)).isTrue();
    }

    @Test
    @DisplayName("진단하지 않은 성향은 범위가 없다")
    void unassignedHasNoRange() {
        assertThat(InvestorRiskRange.of(InvestorType.UNASSIGNED)).isEmpty();
    }
}
