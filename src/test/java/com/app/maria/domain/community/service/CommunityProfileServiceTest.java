package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.repository.CustomerCommunityProfile;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({JpaConfig.class, TransactionConfig.class, CommunityProfileServiceImpl.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityProfileServiceTest {
    @Autowired private CommunityProfileService service;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @BeforeAll
    void createSchema() {
        CommunityTestSchema.create(jdbc);
    }

    @AfterAll
    void dropSchema() {
        CommunityTestSchema.drop(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM customer");
    }

    private long insertCustomer(String nickname) {
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash, nickname)"
                        + " VALUES (?, ?, ?, ?, ?)",
                "홍길동",
                "1990-01-01",
                "ACTIVE",
                "hash-" + System.nanoTime(),
                nickname);
        return jdbc.queryForObject("SELECT MAX(customer_id) FROM customer", Long.class);
    }

    @Test
    @DisplayName("없는 고객이면 CUSTOMER_NOT_FOUND다")
    void unknownCustomer() {
        assertThatThrownBy(() -> service.changeInvestorTypePublic(999_999L, true))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.CUSTOMER_NOT_FOUND));
    }

    @Test
    @DisplayName("성향 공개는 기본이 비공개이고 켜고 끌 수 있다")
    void investorTypePublicToggle() {
        long id = insertCustomer("반도체개미");
        assertThat(customerRepository.findById(id).orElseThrow().isInvestorTypePublic()).isFalse();

        assertThat(service.changeInvestorTypePublic(id, true)).isTrue();
        entityManager.flush();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT investor_type_public FROM customer WHERE customer_id = ?",
                                Boolean.class,
                                id))
                .isTrue();
        assertThat(service.changeInvestorTypePublic(id, false)).isFalse();
    }

    @Test
    @DisplayName("목록용 작성자 조회는 닉네임·성향·공개 여부를 한 번에 돌려준다")
    void communityProfiles() {
        long a = insertCustomer("반도체개미");
        long b = insertCustomer("장기보유러");
        service.changeInvestorTypePublic(a, true);
        entityManager.flush();
        entityManager.clear();

        List<CustomerCommunityProfile> profiles =
                customerRepository.findCommunityProfiles(List.of(a, b));

        assertThat(profiles).hasSize(2);
        CustomerCommunityProfile first =
                profiles.stream().filter(p -> p.getCustomerId() == a).findFirst().orElseThrow();
        assertThat(first.getNickname()).isEqualTo("반도체개미");
        assertThat(first.getInvestorType()).isEqualTo(InvestorType.ACTIVE);
        assertThat(first.getInvestorTypePublic()).isTrue();
        CustomerCommunityProfile second =
                profiles.stream().filter(p -> p.getCustomerId() == b).findFirst().orElseThrow();
        assertThat(second.getNickname()).isEqualTo("장기보유러");
        assertThat(second.getInvestorTypePublic()).isFalse();
    }
}
