package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.dto.request.CustomerSignupRequestDTO;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.PasswordEncoderConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 서비스를 실제 별도 트랜잭션으로 실행해야 하므로 테스트 트랜잭션을 쓰지 않는다. */
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomerSignupConcurrencyTest {
    private static final String RRN = "9001011234567";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private CustomerAuthService service;
    @Autowired private CiHasher ciHasher;

    @BeforeEach
    void createSchemaWithDummyCustomer() {
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
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash) VALUES (?, ?, ?, ?)",
                "홍길동",
                LocalDate.of(1990, 1, 1),
                "NEUTRAL",
                ciHasher.of(RRN));
    }

    @Test
    @DisplayName("같은 더미 고객에게 동시에 가입하면 먼저 끝난 한 건만 성공하고 나머지는 실패한다")
    void onlyOneSignupWinsForSameDummyCustomer() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = dataSource.getConnection()) {
            // 첫 번째 가입이 진행 중인 상황: 다른 트랜잭션이 고객 행을 잠그고 있다.
            first.setAutoCommit(false);
            try (PreparedStatement lock =
                    first.prepareStatement(
                            "SELECT customer_id FROM customer WHERE ci_hash = ? FOR UPDATE")) {
                lock.setString(1, ciHasher.of(RRN));
                lock.executeQuery();
            }

            Future<?> second = executor.submit(() -> service.signup(request("late5678")));

            // 잠금을 기다려야 한다. 기다리지 않고 끝나면 둘 다 계정을 만들 수 있다.
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // 첫 번째 가입이 계정을 만들고 커밋한다.
            try (PreparedStatement auth =
                    first.prepareStatement(
                            "INSERT INTO customer_auth (customer_id, login_id, type)"
                                    + " SELECT customer_id, 'first1234', 'LOCAL' FROM customer")) {
                auth.executeUpdate();
            }
            first.commit();

            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AppException.class)
                    .satisfies(
                            e ->
                                    assertThat(((AppException) e.getCause()).getErrorType())
                                            .isEqualTo(ErrorType.CUSTOMER_SIGNUP_FAILED));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_auth", Integer.class))
                .isEqualTo(1);
    }

    private CustomerSignupRequestDTO request(String loginId) {
        CustomerSignupRequestDTO request = new CustomerSignupRequestDTO();
        request.setLoginId(loginId);
        request.setPassword("password-1234");
        request.setRrn(RRN);
        request.setName("홍길동");
        request.setBirthDate(LocalDate.of(1990, 1, 1));
        return request;
    }
}
