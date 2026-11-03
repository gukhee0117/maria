package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.dto.request.CustomerPasswordChangeRequestDTO;
import com.app.maria.domain.customer.dto.request.CustomerProfileUpdateRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerProfileResponseDTO;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.PasswordEncoderConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
    CustomerProfileServiceImpl.class,
    PasswordEncoderConfig.class,
    CustomerAuthTestBeans.class
})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerProfileServiceTest {
    @MockitoBean private InvestorAssessmentService investorAssessmentService;
    @MockitoBean private AccountMapper accountMapper;

    private static final String CURRENT = "password-1234";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private CustomerProfileService service;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeAll
    void createTestSchema() {
        jdbc.execute("DROP TABLE IF EXISTS investor_assessment");
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
        jdbc.execute("DROP TABLE IF EXISTS customer_auth_password");
        jdbc.execute("DROP TABLE IF EXISTS customer_auth");
        jdbc.execute("DROP TABLE IF EXISTS customer");
        jdbc.execute(
                """
                CREATE TABLE customer (
                    customer_id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL,
                    birth_date DATE NOT NULL, phone VARCHAR(20), email VARCHAR(100), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE,
                    nickname VARCHAR(20) UNIQUE,
                    investor_type_public BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE customer_auth (
                    auth_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id BIGINT NOT NULL REFERENCES customer(customer_id),
                    login_id VARCHAR(50) NOT NULL, type VARCHAR(10) NOT NULL,
                    last_login_at TIMESTAMP, UNIQUE (type, login_id)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE customer_auth_password (
                    auth_password_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    auth_id BIGINT NOT NULL UNIQUE REFERENCES customer_auth(auth_id),
                    password VARCHAR(255) NOT NULL
                )
                """);
    }

    @AfterAll
    void dropTestSchema() {
        jdbc.execute("DROP TABLE IF EXISTS investor_assessment");
    }

    @BeforeEach
    void stubCollaborators() {
        when(investorAssessmentService.getMine(any()))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
    }

    private long customer(String authType) {
        jdbc.update("DELETE FROM customer_auth_password");
        jdbc.update("DELETE FROM customer_auth");
        jdbc.update("DELETE FROM customer");
        jdbc.update(
                "INSERT INTO customer (name, birth_date, phone, email, investor_type, ci_hash)"
                        + " VALUES ('홍길동', ?, '010-1111-2222', 'old@example.com', 'NEUTRAL', 'h')",
                LocalDate.of(1990, 1, 1));
        long customerId = jdbc.queryForObject("SELECT customer_id FROM customer", Long.class);
        jdbc.update(
                "INSERT INTO customer_auth (customer_id, login_id, type) VALUES (?, 'hong1234', ?)",
                customerId,
                authType);
        long authId = jdbc.queryForObject("SELECT auth_id FROM customer_auth", Long.class);
        jdbc.update(
                "INSERT INTO customer_auth_password (auth_id, password) VALUES (?, ?)",
                authId,
                passwordEncoder.encode(CURRENT));
        return customerId;
    }

    private static CustomerProfileUpdateRequestDTO update(String phone, String email) {
        CustomerProfileUpdateRequestDTO request = new CustomerProfileUpdateRequestDTO();
        request.setPhone(phone);
        request.setEmail(email);
        return request;
    }

    private static CustomerPasswordChangeRequestDTO change(String current, String next) {
        CustomerPasswordChangeRequestDTO request = new CustomerPasswordChangeRequestDTO();
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        return request;
    }

    private void assertError(Runnable call, ErrorType expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    @Test
    @DisplayName("내 정보 조회: 이름·생년월일·연락처 원문을 준다")
    void getsProfile() {
        long id = customer("LOCAL");

        CustomerProfileResponseDTO profile = service.get(id);

        assertThat(profile.name()).isEqualTo("홍길동");
        assertThat(profile.birthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(profile.phone()).isEqualTo("010-1111-2222");
        assertThat(profile.email()).isEqualTo("old@example.com");
        assertThat(profile.nickname()).isNull();
    }

    @Test
    @DisplayName("내 정보 조회: 투자성향 상태·점수·최근 3건 이력(최신순)·성향 공개를 함께 준다")
    void includesAssessmentAndPublicFlag() {
        long id = customer("LOCAL");
        for (int score : new int[] {30, 40, 50, 60}) {
            jdbc.update(
                    "INSERT INTO investor_assessment (customer_id, total_score, investor_type,"
                            + " answers_json, assessed_at) VALUES (?, ?, 'NEUTRAL', '{}', ?)",
                    id,
                    score,
                    LocalDateTime.of(2026, 3, 1, 10, 0).plusDays(score));
        }
        jdbc.update("UPDATE customer SET investor_type_public = TRUE WHERE customer_id = ?", id);
        LocalDateTime assessedAt = LocalDateTime.of(2026, 3, 31, 10, 0);
        when(investorAssessmentService.getMine(id))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.NEUTRAL,
                                assessedAt,
                                assessedAt.plusMonths(24),
                                false));

        CustomerProfileResponseDTO profile = service.get(id);

        assertThat(profile.investorType()).isEqualTo(InvestorType.NEUTRAL);
        assertThat(profile.assessed()).isTrue();
        assertThat(profile.assessedAt()).isEqualTo(assessedAt);
        assertThat(profile.totalScore()).isEqualTo(60);
        assertThat(profile.investorTypePublic()).isTrue();
        assertThat(profile.assessmentHistory())
                .extracting(CustomerProfileResponseDTO.AssessmentHistoryItem::totalScore)
                .containsExactly(60, 50, 40);
    }

    @Test
    @DisplayName("진단 기록이 없으면 점수는 null이고 이력은 비어 있다")
    void noAssessmentRecords() {
        long id = customer("LOCAL");

        CustomerProfileResponseDTO profile = service.get(id);

        assertThat(profile.totalScore()).isNull();
        assertThat(profile.assessmentHistory()).isEmpty();
        assertThat(profile.assessed()).isFalse();
    }

    @Test
    @DisplayName("내 정보 조회: RIA 계좌가 있으면 번호·상태·개설일·신청일을 주고 없으면 null이다")
    void includesAccountOrNull() {
        long id = customer("LOCAL");
        assertThat(service.get(id).account()).isNull();

        LocalDateTime applied = LocalDateTime.of(2026, 3, 30, 9, 0);
        when(accountMapper.selectByCustomerId(id))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder()
                                        .accountId(1L)
                                        .customerId(id)
                                        .status(Status.CLOSED)
                                        .accountNo("7319000024")
                                        .openedAt(applied.plusHours(1))
                                        .createdAt(applied)
                                        .build()));

        CustomerProfileResponseDTO.AccountSummary account = service.get(id).account();

        assertThat(account.accountNo()).isEqualTo("7319000024");
        assertThat(account.status()).isEqualTo(Status.CLOSED);
        assertThat(account.openedAt()).isEqualTo(applied.plusHours(1));
        assertThat(account.appliedAt()).isEqualTo(applied);
    }

    @Test
    @DisplayName("없는 고객은 CUSTOMER_NOT_FOUND다")
    void unknownCustomer() {
        assertError(() -> service.get(999_999L), ErrorType.CUSTOMER_NOT_FOUND);
    }

    @Test
    @DisplayName("수정: 보낸 항목만 바뀌고 휴대폰은 하이픈 모양으로 맞춘다")
    void updatesOnlyGivenFields() {
        long id = customer("LOCAL");

        CustomerProfileResponseDTO phoneOnly = service.update(id, update("01099998888", null));
        assertThat(phoneOnly.phone()).isEqualTo("010-9999-8888");
        assertThat(phoneOnly.email()).isEqualTo("old@example.com");

        CustomerProfileResponseDTO emailOnly = service.update(id, update(null, "new@example.com"));
        assertThat(emailOnly.phone()).isEqualTo("010-9999-8888");
        assertThat(emailOnly.email()).isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("10자리 번호는 3-3-4로 나눈다")
    void tenDigitPhone() {
        long id = customer("LOCAL");

        assertThat(service.update(id, update("0161234567", null)).phone())
                .isEqualTo("016-123-4567");
    }

    @Test
    @DisplayName("비밀번호 변경: 현재 비밀번호가 맞으면 새 비밀번호의 BCrypt 해시로 바뀐다")
    void changesPassword() {
        long id = customer("LOCAL");

        service.changePassword(id, change(CURRENT, "new-pass-5678"));
        entityManager.flush();

        String stored =
                jdbc.queryForObject("SELECT password FROM customer_auth_password", String.class);
        assertThat(passwordEncoder.matches("new-pass-5678", stored)).isTrue();
        assertThat(passwordEncoder.matches(CURRENT, stored)).isFalse();
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 거절하고 비밀번호는 그대로다")
    void rejectsWrongCurrentPassword() {
        long id = customer("LOCAL");

        assertError(
                () -> service.changePassword(id, change("wrong-pass-1", "new-pass-5678")),
                ErrorType.CUSTOMER_PASSWORD_MISMATCH);

        String stored =
                jdbc.queryForObject("SELECT password FROM customer_auth_password", String.class);
        assertThat(passwordEncoder.matches(CURRENT, stored)).isTrue();
    }

    @Test
    @DisplayName("현재 비밀번호가 72바이트를 넘는 한글이어도 500이 아니라 불일치로 거절한다")
    void overlongNonAsciiCurrentPasswordIsMismatch() {
        long id = customer("LOCAL");

        assertError(
                () -> service.changePassword(id, change("가".repeat(30), "new-pass-5678")),
                ErrorType.CUSTOMER_PASSWORD_MISMATCH);
    }

    @Test
    @DisplayName("새 비밀번호가 현재와 같으면 거절한다")
    void rejectsSamePassword() {
        long id = customer("LOCAL");

        assertError(
                () -> service.changePassword(id, change(CURRENT, CURRENT)),
                ErrorType.CUSTOMER_PASSWORD_UNCHANGED);
    }

    @Test
    @DisplayName("LOCAL 계정이 없는 고객(소셜 로그인만)은 비밀번호를 바꿀 수 없다")
    void noLocalAccount() {
        long id = customer("GOOGLE");

        assertError(
                () -> service.changePassword(id, change(CURRENT, "new-pass-5678")),
                ErrorType.CUSTOMER_PASSWORD_NOT_SET);
    }

    @Test
    @DisplayName("없는 고객의 비밀번호 변경은 CUSTOMER_NOT_FOUND다")
    void unknownCustomerPasswordChange() {
        assertError(
                () -> service.changePassword(999_999L, change(CURRENT, "new-pass-5678")),
                ErrorType.CUSTOMER_NOT_FOUND);
    }
}
