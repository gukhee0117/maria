package com.app.maria.domain.customer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerEntityTest {
    private static CustomerEntity newCustomer() {
        return CustomerEntity.newUnassigned(
                "홍길동", LocalDate.of(1990, 1, 1), "hash", LocalDateTime.of(2026, 10, 28, 9, 0));
    }

    @Test
    @DisplayName("진단 결과를 반영하면 투자성향이 바뀐다")
    void appliesDiagnosedType() {
        CustomerEntity customer = newCustomer();

        customer.applyInvestorType(InvestorType.ACTIVE);

        assertThat(customer.getInvestorType()).isEqualTo(InvestorType.ACTIVE);
    }

    @Test
    @DisplayName("재진단하면 이전 성향을 덮어쓴다")
    void reassessmentOverwrites() {
        CustomerEntity customer = newCustomer();
        customer.applyInvestorType(InvestorType.ACTIVE);

        customer.applyInvestorType(InvestorType.STABLE);

        assertThat(customer.getInvestorType()).isEqualTo(InvestorType.STABLE);
    }

    @Test
    @DisplayName("미진단은 진단 결과로 반영할 수 없고 기존 성향이 유지된다")
    void rejectsUnassigned() {
        CustomerEntity customer = newCustomer();
        customer.applyInvestorType(InvestorType.NEUTRAL);

        assertThatThrownBy(() -> customer.applyInvestorType(InvestorType.UNASSIGNED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(customer.getInvestorType()).isEqualTo(InvestorType.NEUTRAL);
    }
}
