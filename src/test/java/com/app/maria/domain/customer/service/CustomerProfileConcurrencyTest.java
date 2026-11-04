package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.dto.request.CustomerProfileUpdateRequestDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.PasswordEncoderConfig;
import com.app.maria.global.config.TransactionConfig;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 서비스를 실제 별도 트랜잭션 둘로 실행해야 하므로 테스트 트랜잭션을 쓰지 않는다. */
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomerProfileConcurrencyTest {
    @MockitoBean private InvestorAssessmentService investorAssessmentService;
    @MockitoBean private AccountMapper accountMapper;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerProfileService service;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("jpaTransactionManager")
    private PlatformTransactionManager transactionManager;

    private long customerId;

    @BeforeEach
    void createSchema() {
        when(investorAssessmentService.getMine(any()))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
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
        jdbc.update(
                "INSERT INTO customer (name, birth_date, phone, investor_type, ci_hash)"
                        + " VALUES ('홍길동', ?, '010-1111-2222', 'NEUTRAL', 'h')",
                LocalDate.of(1990, 1, 1));
        customerId = jdbc.queryForObject("SELECT customer_id FROM customer", Long.class);
    }

    private static CustomerProfileUpdateRequestDTO update(String phone, String email) {
        CustomerProfileUpdateRequestDTO request = new CustomerProfileUpdateRequestDTO();
        request.setPhone(phone);
        request.setEmail(email);
        return request;
    }

    @Test
    @DisplayName("휴대폰만 바꾸는 요청과 이메일만 바꾸는 요청이 겹쳐도 둘 다 반영된다(서로 되돌리지 않는다)")
    void concurrentPartialUpdatesDoNotRevertEachOther() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch phoneSaved = new CountDownLatch(1);
        try {
            // A: 휴대폰을 바꾸고 커밋하기 전까지 트랜잭션을 연 채로 둔다.
            Future<?> a =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        service.update(
                                                                customerId,
                                                                update("01099998888", null));
                                                        phoneSaved.countDown();
                                                        sleep(700);
                                                    }));
            // B: A가 휴대폰을 바꾼 직후, 아직 커밋하기 전에 이메일만 바꾼다.
            Future<?> b =
                    executor.submit(
                            () -> {
                                await(phoneSaved);
                                service.update(customerId, update(null, "new@example.com"));
                            });
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT phone, email FROM customer");
        assertThat(row.get("PHONE")).isEqualTo("010-9999-8888");
        assertThat(row.get("EMAIL")).isEqualTo("new@example.com");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
