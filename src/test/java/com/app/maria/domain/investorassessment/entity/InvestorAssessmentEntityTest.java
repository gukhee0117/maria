package com.app.maria.domain.investorassessment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.customer.entity.InvestorType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvestorAssessmentEntityTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 9, 0);

    @Test
    @DisplayName("진단 결과를 이력으로 만든다")
    void createsHistoryRow() {
        InvestorAssessmentEntity row =
                InvestorAssessmentEntity.of(1L, 33, InvestorType.ACTIVE, "{}", AT);

        assertThat(row.getCustomerId()).isEqualTo(1L);
        assertThat(row.getTotalScore()).isEqualTo(33);
        assertThat(row.getInvestorType()).isEqualTo(InvestorType.ACTIVE);
        assertThat(row.getAssessedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("미진단은 이력으로 저장할 수 없다")
    void rejectsUnassigned() {
        assertThatThrownBy(
                        () -> InvestorAssessmentEntity.of(1L, 0, InvestorType.UNASSIGNED, "{}", AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
