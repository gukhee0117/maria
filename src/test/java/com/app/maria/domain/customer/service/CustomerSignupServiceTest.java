package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.dto.request.CustomerSignupRequestDTO;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.repository.CustomerAuthPasswordRepository;
import com.app.maria.domain.customer.repository.CustomerAuthRepository;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.PasswordEncoderConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class CustomerSignupServiceTest {
    private static final String RRN = "9001011234567";
    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 1);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerAuthService service;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CiHasher ciHasher;

    @BeforeAll
    void createTestSchema() {
        // 테스트 전용 H2에 실제 DDL의 컬럼·제약을 구성한다.
        // 같은 H2를 다른 테스트 클래스와 공유하므로 먼저 지운다.
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
    }

    @Test
    @DisplayName("이미 있는 더미 고객에게는 계정만 붙인다")
    void attachesAuthToExistingDummyCustomer() {
        long customerId = insertDummyCustomer("홍길동", BIRTH);

        CustomerPrincipal principal = service.signup(request("hong1234", "홍길동", BIRTH));

        assertThat(principal).isEqualTo(new CustomerPrincipal(customerId, "hong1234", "홍길동"));
        assertThat(count("customer")).isEqualTo(1);
        assertThat(count("customer_auth")).isEqualTo(1);
    }

    @Test
    @DisplayName("처음 보는 주민번호면 미진단 고객을 새로 만든다")
    void createsNewCustomerWhenCiHashAbsent() {
        service.signup(request("hong1234", "홍길동", BIRTH));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM customer");
        assertThat(row.get("INVESTOR_TYPE")).isEqualTo("UNASSIGNED");
        assertThat(row.get("CI_HASH")).isEqualTo(ciHasher.of(RRN));
        assertThat(row.get("CREATED_AT").toString()).startsWith("2026-10-25 12:00:00");
    }

    @Test
    @DisplayName("비밀번호는 BCrypt 해시로만 저장한다")
    void storesBcryptHashNotRawPassword() {
        service.signup(request("hong1234", "홍길동", BIRTH));

        String stored =
                jdbc.queryForObject("SELECT password FROM customer_auth_password", String.class);
        assertThat(stored).isNotEqualTo("password-1234");
        assertThat(passwordEncoder.matches("password-1234", stored)).isTrue();
    }

    @Test
    @DisplayName("주민번호 원문은 어느 테이블에도 저장하지 않는다")
    void neverStoresRrn() {
        service.signup(request("hong1234", "홍길동", BIRTH));

        for (String table : List.of("customer", "customer_auth", "customer_auth_password")) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table).toString()).doesNotContain(RRN);
        }
    }

    @Test
    @DisplayName("정보 불일치와 이미 가입됨을 같은 실패로 응답한다")
    void unifiesFailureForMismatchAndAlreadyRegistered() {
        insertDummyCustomer("홍길동", BIRTH);

        assertSignupFailed(request("hong1234", "김철수", BIRTH));
        assertSignupFailed(request("hong1234", "홍길동", LocalDate.of(1991, 1, 1)));

        service.signup(request("hong1234", "홍길동", BIRTH));
        assertSignupFailed(request("other5678", "홍길동", BIRTH));
        assertThat(count("customer_auth")).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 쓰는 아이디면 중복으로 거절하고 고객을 만들지 않는다")
    void rejectsDuplicateLoginId() {
        service.signup(request("hong1234", "홍길동", BIRTH));
        CustomerSignupRequestDTO other = request("hong1234", "김철수", BIRTH);
        other.setRrn("9202021234567");

        assertThatThrownBy(() -> service.signup(other))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_LOGIN_ID_DUPLICATED));
        assertThat(count("customer")).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 가입으로 저장이 유니크 위반이 되면 500이 아니라 가입 실패로 응답한다")
    void mapsConstraintViolationToSignupFailed() {
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        when(customerRepository.findByCiHashForUpdate(any())).thenReturn(Optional.empty());
        when(customerRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_customer_ci_hash"));
        CustomerAuthRepository authRepository = mock(CustomerAuthRepository.class);
        CustomerAuthServiceImpl racing =
                new CustomerAuthServiceImpl(
                        authRepository,
                        customerRepository,
                        mock(CustomerAuthPasswordRepository.class),
                        passwordEncoder,
                        ciHasher,
                        Clock.systemDefaultZone());

        assertThatThrownBy(() -> racing.signup(request("hong1234", "홍길동", BIRTH)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_SIGNUP_FAILED));
    }

    private CustomerSignupRequestDTO request(String loginId, String name, LocalDate birth) {
        CustomerSignupRequestDTO request = new CustomerSignupRequestDTO();
        request.setLoginId(loginId);
        request.setPassword("password-1234");
        request.setRrn(RRN);
        request.setName(name);
        request.setBirthDate(birth);
        return request;
    }

    private long insertDummyCustomer(String name, LocalDate birth) {
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash) VALUES (?, ?, ?, ?)",
                name,
                birth,
                InvestorType.NEUTRAL.name(),
                ciHasher.of(RRN));
        return jdbc.queryForObject(
                "SELECT customer_id FROM customer WHERE ci_hash = ?", Long.class, ciHasher.of(RRN));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertSignupFailed(CustomerSignupRequestDTO request) {
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_SIGNUP_FAILED));
    }
}
