package com.app.maria.domain.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.dto.request.CustomerLoginRequestDTO;
import com.app.maria.domain.customer.entity.CustomerAuthEntity;
import com.app.maria.domain.customer.entity.CustomerAuthEntity.AuthType;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.service.CustomerAuthService;
import com.app.maria.domain.customer.service.CustomerAuthServiceImpl;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.PasswordEncoderConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
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
    CustomerAuthServiceImpl.class,
    PasswordEncoderConfig.class,
    CustomerAuthTestBeans.class
})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerAuthRepositoryTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerAuthRepository repository;
    @Autowired private CustomerAuthService service;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager legacyTransactionManager;

    @Autowired
    @Qualifier("jpaTransactionManager")
    private PlatformTransactionManager jpaTransactionManager;

    @BeforeAll
    void createTestSchema() {
        // 테스트 전용 H2에 기존 DDL의 컬럼·관계를 구성한다. 실제 MariaDB는 사용하지 않는다.
        // 같은 H2를 다른 테스트 클래스와 공유하므로 먼저 지운다.
        jdbc.execute("DROP TABLE IF EXISTS customer_auth_password");
        jdbc.execute("DROP TABLE IF EXISTS customer_auth");
        jdbc.execute("DROP TABLE IF EXISTS customer");
        jdbc.execute(
                """
                CREATE TABLE customer (
                    customer_id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL,
                    birth_date DATE NOT NULL, phone VARCHAR(20), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE customer_auth (
                    auth_id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL REFERENCES customer(customer_id),
                    login_id VARCHAR(50) NOT NULL, type VARCHAR(10) NOT NULL, last_login_at TIMESTAMP,
                    UNIQUE (type, login_id)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE customer_auth_password (
                    auth_password_id BIGINT PRIMARY KEY,
                    auth_id BIGINT NOT NULL UNIQUE REFERENCES customer_auth(auth_id),
                    password VARCHAR(255) NOT NULL
                )
                """);
    }

    @BeforeEach
    void seedCustomer() {
        jdbc.update(
                """
                INSERT INTO customer (customer_id, name, birth_date, investor_type, ci_hash)
                VALUES (1, '테스트고객', '2000-01-01', 'NEUTRAL', 'test-only-ci')
                """);
    }

    @Test
    @DisplayName("JPA로 기존 LOCAL 계정과 BCrypt 해시를 조회해 로그인한다")
    void authenticatesLocalCustomerWithJpa() {
        insertAuth(10, "customer1", "LOCAL", true);

        CustomerPrincipal principal = service.authenticate(login("customer1", "test-password"));

        assertThat(principal).isEqualTo(new CustomerPrincipal(1L, "customer1", "테스트고객"));
    }

    @Test
    @DisplayName("로그인에 성공하면 마지막 로그인 시각을 실제 시각으로 갱신한다")
    void updatesLastLoginAtOnSuccess() {
        insertAuth(10, "customer1", "LOCAL", true);

        service.authenticate(login("customer1", "test-password"));
        entityManager.flush();

        assertThat(
                        jdbc.queryForObject(
                                        "SELECT last_login_at FROM customer_auth WHERE auth_id = 10",
                                        java.sql.Timestamp.class)
                                .toLocalDateTime())
                .isEqualTo(java.time.LocalDateTime.of(2026, 10, 25, 12, 0, 0));
    }

    @Test
    @DisplayName("로그인에 실패하면 마지막 로그인 시각을 바꾸지 않는다")
    void keepsLastLoginAtOnFailure() {
        insertAuth(10, "customer1", "LOCAL", true);

        assertLoginFailed("customer1", "wrong-password");
        entityManager.flush();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_login_at FROM customer_auth WHERE auth_id = 10",
                                java.sql.Timestamp.class))
                .isNull();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 고객 로그인 실패로 응답한다")
    void rejectsIncorrectPassword() {
        insertAuth(10, "customer1", "LOCAL", true);
        assertLoginFailed("customer1", "wrong-password");
    }

    @Test
    @DisplayName("없는 아이디도 동일한 로그인 실패로 처리한다")
    void rejectsMissingLoginId() {
        assertLoginFailed("missing", "test-password");
    }

    @Test
    @DisplayName("동일 아이디의 소셜 계정을 LOCAL 계정으로 처리하지 않는다")
    void selectsOnlyLocalAccount() {
        insertAuth(10, "customer1", "GOOGLE", false);
        assertLoginFailed("customer1", "test-password");

        insertAuth(11, "customer1", "LOCAL", true);
        assertThat(service.authenticate(login("customer1", "test-password")).loginId())
                .isEqualTo("customer1");
    }

    @Test
    @DisplayName("비밀번호 행이 없는 LOCAL 계정을 거절한다")
    void rejectsMissingPasswordRecord() {
        insertAuth(10, "customer1", "LOCAL", false);
        assertLoginFailed("customer1", "test-password");
    }

    @Test
    @DisplayName("같은 종류의 인증수단은 아이디가 중복될 수 없고, 종류가 다르면 같은 아이디를 허용한다")
    void rejectsDuplicateLoginIdPerType() {
        insertAuth(10, "customer1", "LOCAL", false);

        assertThatThrownBy(() -> insertAuth(11, "customer1", "LOCAL", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertAuth(12, "customer1", "GOOGLE", false);
    }

    @Test
    @DisplayName("미진단(UNASSIGNED) 투자성향을 JPA로 읽는다")
    void readsUnassignedInvestorType() {
        jdbc.update("UPDATE customer SET investor_type = 'UNASSIGNED' WHERE customer_id = 1");
        entityManager.clear();

        assertThat(entityManager.find(CustomerEntity.class, 1L).getInvestorType())
                .isEqualTo(InvestorType.UNASSIGNED);
    }

    @Test
    @DisplayName("고객과 비밀번호를 함께 조회해 영속성 컨텍스트 밖에서도 접근 가능하다")
    void fetchesRequiredRelations() {
        insertAuth(10, "customer1", "LOCAL", true);
        List<CustomerAuthEntity> credentials =
                repository.findTop2ByLoginIdAndTypeOrderByAuthIdAsc("customer1", AuthType.LOCAL);
        entityManager.clear();

        assertThat(credentials.get(0).getCustomer().getName()).isEqualTo("테스트고객");
        assertThat(
                        passwordEncoder.matches(
                                "test-password",
                                credentials.get(0).getPassword().getPasswordHash()))
                .isTrue();
    }

    @Test
    @DisplayName("1차 JDBC와 2차 JPA의 트랜잭션 관리자가 각각 유지된다")
    void keepsLegacyAndJpaTransactionsSeparate() {
        assertThat(legacyTransactionManager).isInstanceOf(DataSourceTransactionManager.class);
        assertThat(jpaTransactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(entityManager.isJoinedToTransaction()).isTrue();
    }

    private void insertAuth(long id, String loginId, String type, boolean withPassword) {
        jdbc.update(
                "INSERT INTO customer_auth (auth_id, customer_id, login_id, type) VALUES (?, 1, ?, ?)",
                id,
                loginId,
                type);
        if (withPassword) {
            jdbc.update(
                    "INSERT INTO customer_auth_password (auth_password_id, auth_id, password) VALUES (?, ?, ?)",
                    id,
                    id,
                    passwordEncoder.encode("test-password"));
        }
    }

    private CustomerLoginRequestDTO login(String loginId, String password) {
        CustomerLoginRequestDTO request = new CustomerLoginRequestDTO();
        request.setLoginId(loginId);
        request.setPassword(password);
        return request;
    }

    private void assertLoginFailed(String loginId, String password) {
        assertThatThrownBy(() -> service.authenticate(login(loginId, password)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_LOGIN_FAILED));
    }
}
