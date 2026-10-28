package com.app.maria.domain.investorassessment.service;

import static com.app.maria.domain.investorassessment.service.InvestorAssessmentTestData.aggressiveAnswers;
import static com.app.maria.domain.investorassessment.service.InvestorAssessmentTestData.stableAnswers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentResultResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorQuestionResponseDTO;
import com.app.maria.domain.investorassessment.entity.choice.ExperienceChoice;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({
    JpaConfig.class,
    TransactionConfig.class,
    InvestorAssessmentServiceImpl.class,
    InvestorAssessmentServiceTest.JsonBeans.class
})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvestorAssessmentServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 9, 0);

    @TestConfiguration
    static class JsonBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private InvestorAssessmentService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private BusinessClockService clock;

    @BeforeAll
    void createTestSchema() {
        // 같은 H2를 다른 테스트 클래스와 공유한다. 다른 클래스가 customer를 지울 수 있도록 FK는 걸지 않는다.
        jdbc.execute("DROP TABLE IF EXISTS investor_assessment");
        jdbc.execute("DROP TABLE IF EXISTS customer_auth_password");
        jdbc.execute("DROP TABLE IF EXISTS customer_auth");
        jdbc.execute("DROP TABLE IF EXISTS customer");
        jdbc.execute(
                """
                CREATE TABLE customer (
                    customer_id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL,
                    birth_date DATE NOT NULL, phone VARCHAR(20), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE investor_assessment (
                    investor_assessment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id BIGINT NOT NULL, total_score INT NOT NULL,
                    investor_type VARCHAR(20) NOT NULL, answers_json VARCHAR(4000) NOT NULL,
                    assessed_at TIMESTAMP NOT NULL,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @AfterAll
    void dropTestSchema() {
        jdbc.execute("DROP TABLE IF EXISTS investor_assessment");
    }

    private long insertCustomer(String investorType) {
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash) VALUES (?, ?, ?, ?)",
                "홍길동",
                "1990-01-01",
                investorType,
                "hash-" + System.nanoTime());
        return jdbc.queryForObject("SELECT MAX(customer_id) FROM customer", Long.class);
    }

    private String currentType(long customerId) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT investor_type FROM customer WHERE customer_id = ?",
                String.class,
                customerId);
    }

    private int assessmentCount(long customerId) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM investor_assessment WHERE customer_id = ?",
                Integer.class,
                customerId);
    }

    @Test
    @DisplayName("제출하면 진단 기록이 남고 고객의 현재 성향이 바뀐다")
    void submitSavesHistoryAndUpdatesCustomer() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);

        InvestorAssessmentResultResponseDTO result =
                service.submit(customerId, aggressiveAnswers());

        assertThat(result.getTotalScore()).isEqualTo(50);
        assertThat(result.getInvestorType()).isEqualTo(InvestorType.AGGRESSIVE);
        assertThat(result.getAssessedAt()).isEqualTo(NOW);
        assertThat(result.getExpiresAt()).isEqualTo(NOW.plusMonths(24));
        assertThat(currentType(customerId)).isEqualTo("AGGRESSIVE");
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM investor_assessment WHERE customer_id = ?", customerId);
        assertThat(row.get("TOTAL_SCORE")).isEqualTo(50);
        assertThat(row.get("INVESTOR_TYPE")).isEqualTo("AGGRESSIVE");
    }

    @Test
    @DisplayName("음수 합계는 0점으로 저장하고 안정형이 된다")
    void negativeTotalIsStoredAsZero() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);

        InvestorAssessmentResultResponseDTO result = service.submit(customerId, stableAnswers());

        assertThat(result.getTotalScore()).isZero();
        assertThat(result.getInvestorType()).isEqualTo(InvestorType.STABLE);
        assertThat(currentType(customerId)).isEqualTo("STABLE");
    }

    @Test
    @DisplayName("재진단하면 기록이 하나 더 쌓이고 현재 성향은 최신 결과가 된다")
    void reassessmentAddsRowAndOverwritesType() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);
        service.submit(customerId, aggressiveAnswers());

        when(clock.now()).thenReturn(NOW.plusDays(1));
        service.submit(customerId, stableAnswers());

        assertThat(assessmentCount(customerId)).isEqualTo(2);
        assertThat(currentType(customerId)).isEqualTo("STABLE");
    }

    @Test
    @DisplayName("없는 고객이면 거절하고 기록을 남기지 않는다")
    void unknownCustomerLeavesNoRow() {
        when(clock.now()).thenReturn(NOW);

        assertThatThrownBy(() -> service.submit(999_999L, aggressiveAnswers()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorType())
                .isEqualTo(ErrorType.CUSTOMER_NOT_FOUND);
        assertThat(assessmentCount(999_999L)).isZero();
    }

    @Test
    @DisplayName("투자경험을 어떤 순서로 보내도 저장되는 응답 JSON은 같다")
    void answersJsonIsStableAcrossExperienceOrder() {
        when(clock.now()).thenReturn(NOW);
        long first = insertCustomer("UNASSIGNED");
        long second = insertCustomer("UNASSIGNED");
        InvestorAssessmentSubmitRequestDTO a = aggressiveAnswers();
        a.setExperience(
                InvestorAssessmentTestData.experience(
                        ExperienceChoice.STOCKS_AND_EQUITY_FUNDS,
                        ExperienceChoice.HIGH_RISK_PRODUCTS,
                        ExperienceChoice.DEPOSITS));
        InvestorAssessmentSubmitRequestDTO b = aggressiveAnswers();
        b.setExperience(
                InvestorAssessmentTestData.experience(
                        ExperienceChoice.DEPOSITS,
                        ExperienceChoice.STOCKS_AND_EQUITY_FUNDS,
                        ExperienceChoice.HIGH_RISK_PRODUCTS));

        service.submit(first, a);
        service.submit(second, b);
        entityManager.flush();

        String jsonA =
                jdbc.queryForObject(
                        "SELECT answers_json FROM investor_assessment WHERE customer_id = ?",
                        String.class,
                        first);
        String jsonB =
                jdbc.queryForObject(
                        "SELECT answers_json FROM investor_assessment WHERE customer_id = ?",
                        String.class,
                        second);
        assertThat(jsonA).isEqualTo(jsonB);
        assertThat(jsonA)
                .contains(
                        "\"experience\":[\"HIGH_RISK_PRODUCTS\",\"STOCKS_AND_EQUITY_FUNDS\",\"DEPOSITS\"]");
        assertThat(jsonA).contains("\"age\":\"FROM_30_TO_64\"");
        assertThat(jsonA).doesNotContain("experienceConsistent");
        assertThat(jsonA).doesNotContain("experienceUnique");
    }

    @Test
    @DisplayName("설문을 안 한 고객은 미진단으로 나온다")
    void unassignedCustomerIsNotAssessed() {
        long customerId = insertCustomer("UNASSIGNED");

        InvestorAssessmentStatusResponseDTO status = service.getMine(customerId);

        assertThat(status.getInvestorType()).isEqualTo(InvestorType.UNASSIGNED);
        assertThat(status.isAssessed()).isFalse();
        assertThat(status.isExpired()).isFalse();
        assertThat(status.getAssessedAt()).isNull();
    }

    @Test
    @DisplayName("성향 값만 있고 진단 기록이 없는 고객은 진단한 것으로 보고 만료로 보지 않는다")
    void customerWithoutRecordIsAssessedAndNotExpired() {
        long customerId = insertCustomer("NEUTRAL");
        when(clock.now()).thenReturn(NOW.plusYears(10));

        InvestorAssessmentStatusResponseDTO status = service.getMine(customerId);

        assertThat(status.getInvestorType()).isEqualTo(InvestorType.NEUTRAL);
        assertThat(status.isAssessed()).isTrue();
        assertThat(status.isExpired()).isFalse();
        assertThat(status.getAssessedAt()).isNull();
        assertThat(status.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("진단 직후에는 만료가 아니다")
    void freshAssessmentIsNotExpired() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);
        service.submit(customerId, aggressiveAnswers());

        InvestorAssessmentStatusResponseDTO status = service.getMine(customerId);

        assertThat(status.isAssessed()).isTrue();
        assertThat(status.isExpired()).isFalse();
        assertThat(status.getAssessedAt()).isEqualTo(NOW);
        assertThat(status.getExpiresAt()).isEqualTo(NOW.plusMonths(24));
    }

    @Test
    @DisplayName("진단 24개월 정각부터 만료이고, 1초 전까지는 유효하다")
    void expiryBoundaryIsInclusive() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);
        service.submit(customerId, aggressiveAnswers());
        LocalDateTime expiresAt = NOW.plusMonths(24);

        when(clock.now()).thenReturn(expiresAt.minusSeconds(1));
        assertThat(service.getMine(customerId).isExpired()).isFalse();

        when(clock.now()).thenReturn(expiresAt);
        assertThat(service.getMine(customerId).isExpired()).isTrue();
    }

    @Test
    @DisplayName("재진단하면 만료 기준이 최신 진단으로 옮겨진다")
    void reassessmentMovesExpiry() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);
        service.submit(customerId, aggressiveAnswers());
        LocalDateTime later = NOW.plusMonths(25);
        when(clock.now()).thenReturn(later);
        assertThat(service.getMine(customerId).isExpired()).isTrue();

        service.submit(customerId, stableAnswers());

        InvestorAssessmentStatusResponseDTO status = service.getMine(customerId);
        assertThat(status.isExpired()).isFalse();
        assertThat(status.getAssessedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("업무시계를 되돌려 재진단해도 나중에 저장한 진단이 현재 기준이다")
    void latestSavedAssessmentWinsEvenIfClockWasRewound() {
        long customerId = insertCustomer("UNASSIGNED");
        when(clock.now()).thenReturn(NOW);
        service.submit(customerId, aggressiveAnswers());
        LocalDateTime rewound = NOW.minusMonths(3);
        when(clock.now()).thenReturn(rewound);
        service.submit(customerId, stableAnswers());

        when(clock.now()).thenReturn(rewound.plusDays(1));
        InvestorAssessmentStatusResponseDTO status = service.getMine(customerId);

        assertThat(status.getInvestorType()).isEqualTo(InvestorType.STABLE);
        assertThat(status.getAssessedAt()).isEqualTo(rewound);
    }

    @Test
    @DisplayName("없는 고객의 상태 조회는 거절한다")
    void statusOfUnknownCustomerIsRejected() {
        assertThatThrownBy(() -> service.getMine(999_999L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorType())
                .isEqualTo(ErrorType.CUSTOMER_NOT_FOUND);
    }

    @Test
    @DisplayName("문항 목록은 아홉 문항이고 보기에는 배점이 없다")
    void questionsListHasNineWithoutScores() {
        List<InvestorQuestionResponseDTO> questions = service.questions();

        assertThat(questions).hasSize(9);
        assertThat(questions.get(0).getAnswerKey()).isEqualTo("age");
        assertThat(questions.get(0).getChoices().get(0).getValue()).isEqualTo("UNDER_19");
        assertThat(questions.get(0).getChoices().get(0).getLabel()).isEqualTo("만 19세 미만");
        assertThat(
                        questions.stream()
                                .filter(InvestorQuestionResponseDTO::isMultiple)
                                .map(InvestorQuestionResponseDTO::getAnswerKey))
                .containsExactly("experience");
        assertThat(InvestorQuestionResponseDTO.Choice.class.getDeclaredFields())
                .extracting(f -> f.getName())
                .containsExactlyInAnyOrder("value", "label");
    }
}
