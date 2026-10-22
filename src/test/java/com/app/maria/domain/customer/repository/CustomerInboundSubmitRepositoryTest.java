package com.app.maria.domain.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.account.repository.AccountRepository;
import com.app.maria.domain.inbound.repository.InboundDetailRepository;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import java.math.BigDecimal;
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
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.datasource.name=customer-inbound-submit-repository-test"
        })
@Import({JpaConfig.class, TransactionConfig.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerInboundSubmitRepositoryTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accountRepository;
    @Autowired private InboundDetailRepository inboundDetailRepository;

    @BeforeAll
    void createSchema() {
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
                CREATE TABLE account (
                    account_id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL UNIQUE REFERENCES customer(customer_id),
                    status VARCHAR(20) NOT NULL, opened_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    account_no VARCHAR(10) UNIQUE,
                    limit_amount DECIMAL(15,0) NOT NULL, amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                    benefit VARCHAR(12), last_domestic_trade_id BIGINT
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE foreign_product (
                    foreign_product_id BIGINT PRIMARY KEY, ticker VARCHAR(20) NOT NULL,
                    name VARCHAR(100) NOT NULL, market VARCHAR(50) NOT NULL,
                    currency VARCHAR(10), type VARCHAR(15) NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE inbound (
                    inbound_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    account_id BIGINT NOT NULL REFERENCES account(account_id),
                    requested_qty DECIMAL(15,4) NOT NULL,
                    current_holding_at_request DECIMAL(15,4),
                    approved_qty DECIMAL(15,4) NOT NULL,
                    processed_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE inbound_detail (
                    inbound_detail_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    inbound_id BIGINT NOT NULL REFERENCES inbound(inbound_id),
                    foreign_product_id BIGINT NOT NULL REFERENCES foreign_product(foreign_product_id),
                    registrable_stock_id BIGINT,
                    source_broker VARCHAR(20),
                    source_general_account_id BIGINT,
                    account_type VARCHAR(30),
                    recorded_at TIMESTAMP NOT NULL,
                    purchase_date TIMESTAMP NOT NULL,
                    purchase_price DECIMAL(15,4) NOT NULL,
                    purchase_currency VARCHAR(10) NOT NULL,
                    purchase_fx_rate DECIMAL(15,4) NOT NULL,
                    qty DECIMAL(15,4) NOT NULL,
                    current_qty DECIMAL(15,4) NOT NULL
                )
                """);
    }

    @BeforeEach
    void seedData() {
        jdbc.update(
                """
                INSERT INTO customer (customer_id, name, birth_date, investor_type, ci_hash)
                VALUES (1, '테스트고객', '2000-01-01', 'NEUTRAL', 'test-ci-hash')
                """);
        jdbc.update(
                """
                INSERT INTO account
                    (account_id, customer_id, status, opened_at, account_no, limit_amount, amount)
                VALUES (10, 1, 'OPENED', '2026-10-01 09:00:00', '0000000010', 50000000, 0)
                """);
        jdbc.update(
                """
                INSERT INTO foreign_product
                    (foreign_product_id, ticker, name, market, currency, type)
                VALUES (100, 'AAPL', 'Apple Inc.', 'NASDAQ', 'USD', 'FOREIGN_STOCK')
                """);
        jdbc.update(
                """
                INSERT INTO inbound
                    (inbound_id, account_id, requested_qty, approved_qty, processed_at)
                VALUES (1, 10, 100, 80, '2026-10-10 10:00:00')
                """);
        // lot 300 — registrable_stock_id = 300 (알려진 원본)
        // lot 301 — registrable_stock_id = 301 (알려진 원본, 별도 신청)
        // null 원본 — registrable_stock_id = NULL (1차 이관 이력)
        jdbc.update(
                """
                INSERT INTO inbound_detail
                    (inbound_detail_id, inbound_id, foreign_product_id,
                     registrable_stock_id, source_general_account_id,
                     recorded_at, purchase_date, purchase_price, purchase_currency,
                     purchase_fx_rate, qty, current_qty)
                VALUES
                    (1, 1, 100, 300, 200, '2026-10-10 10:00:00', '2025-11-01 00:00:00',
                     150.0000, 'USD', 1350.0000, 50.0000, 50.0000),
                    (2, 1, 100, 301, 200, '2026-10-10 10:00:00', '2025-11-02 00:00:00',
                     155.0000, 'USD', 1360.0000, 30.0000, 30.0000),
                    (3, 1, 100, NULL, 200, '2026-10-10 10:00:00', '2025-11-03 00:00:00',
                     160.0000, 'USD', 1370.0000, 20.0000, 20.0000)
                """);
    }

    @Test
    @DisplayName("특정 registrableStockId에 해당하는 계좌의 누적 승인 수량을 합산한다")
    void sumApprovedQtyByAccountAndRegistrableStockId_returnsSumForMatchingRows() {
        BigDecimal sum =
                inboundDetailRepository.sumApprovedQtyByAccountAndRegistrableStockId(10L, 300L);

        assertThat(sum).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("다른 registrableStockId 행은 합산에 포함하지 않는다")
    void sumApprovedQtyByAccountAndRegistrableStockId_excludesOtherStockIds() {
        BigDecimal sum =
                inboundDetailRepository.sumApprovedQtyByAccountAndRegistrableStockId(10L, 301L);

        assertThat(sum).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("해당 registrableStockId 행이 없으면 0을 반환한다")
    void sumApprovedQtyByAccountAndRegistrableStockId_returnsZeroWhenNoRows() {
        BigDecimal sum =
                inboundDetailRepository.sumApprovedQtyByAccountAndRegistrableStockId(10L, 999L);

        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("선택 출처에 registrableStockId=NULL 이력이 있으면 true를 반환한다")
    void existsNullOrigin_returnsTrueWhenNullOriginRowPresent() {
        boolean exists =
                inboundDetailRepository.existsNullOriginByAccountAndProductAndSourceGeneralAccount(
                        10L, 100L, 200L);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("NULL 원본 이력이 없으면 false를 반환한다")
    void existsNullOrigin_returnsFalseWhenNoNullOriginRows() {
        // 별도 계좌·출처에 NULL이 없는 경우
        boolean exists =
                inboundDetailRepository.existsNullOriginByAccountAndProductAndSourceGeneralAccount(
                        10L, 100L, 999L);

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("비관적 잠금 조회는 일반 조회와 동일하게 계좌와 고객 정보를 함께 반환한다")
    void findForUpdate_returnsAccountWithCustomerEagerlyLoaded() {
        var account = accountRepository.findByCustomer_CustomerIdForUpdate(1L).orElseThrow();

        assertThat(account.getAccountId()).isEqualTo(10L);
        assertThat(account.getCustomer().getCustomerId()).isEqualTo(1L);
        assertThat(account.getCustomer().getCiHash()).isEqualTo("test-ci-hash");
    }

    @Test
    @DisplayName("존재하지 않는 고객 ID로 비관적 잠금 조회 시 빈 Optional을 반환한다")
    void findForUpdate_returnsEmptyForUnknownCustomer() {
        assertThat(accountRepository.findByCustomer_CustomerIdForUpdate(999L)).isEmpty();
    }
}
