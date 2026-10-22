package com.app.maria.domain.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.account.entity.AccountEntity;
import com.app.maria.domain.account.repository.AccountRepository;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.foreignproduct.repository.ForeignProductRepository;
import com.app.maria.domain.foreignproduct.type.ForeignProductType;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import jakarta.persistence.EntityManager;
import java.util.List;
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
@Import({JpaConfig.class, TransactionConfig.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerInboundLookupRepositoryTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ForeignProductRepository foreignProductRepository;
    @Autowired private EntityManager entityManager;

    @BeforeAll
    void createTestSchema() {
        // 테스트 전용 H2에만 생성한다. 기존 MariaDB DDL과 데이터는 변경하지 않는다.
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
    }

    @BeforeEach
    void seedLookupData() {
        jdbc.update(
                """
                INSERT INTO customer (customer_id, name, birth_date, investor_type, ci_hash)
                VALUES (1, '테스트고객1', '2000-01-01', 'NEUTRAL', 'lookup-ci-1'),
                       (2, '테스트고객2', '2000-01-02', 'ACTIVE', 'lookup-ci-2'),
                       (3, '계좌없는고객', '2000-01-03', 'STABLE', 'lookup-ci-3')
                """);
        jdbc.update(
                """
                INSERT INTO account
                    (account_id, customer_id, status, opened_at, account_no, limit_amount,
                     amount, benefit, last_domestic_trade_id)
                VALUES (10, 1, 'OPENED', '2026-10-20 10:30:00', '0000000010', 12345678,
                        987654321012345, 'POSSIBLE', 123),
                       (20, 2, 'CLOSED', '2026-10-20 11:30:00', '0000000020', 50000000,
                        0, NULL, NULL)
                """);
        jdbc.update(
                """
                INSERT INTO foreign_product (foreign_product_id, ticker, name, market, currency, type)
                VALUES (1, 'AAPL', 'Apple Inc.', 'NASDAQ', 'USD', 'FOREIGN_STOCK'),
                       (2, 'MSFT', 'Test ETF', 'NASDAQ', 'USD', 'ETF'),
                       (3, 'AAPL', 'Test ETN', 'OTHER', NULL, 'ETN')
                """);
    }

    @Test
    @DisplayName("고객 ID로 본인 계좌만 조회하고 고객 정보도 함께 가져온다")
    void findsOnlyRequestedCustomersAccount() {
        AccountEntity account = accountRepository.findByCustomer_CustomerId(1L).orElseThrow();
        entityManager.clear();

        assertThat(account.getAccountId()).isEqualTo(10L);
        assertThat(account.getCustomer().getCustomerId()).isEqualTo(1L);
        assertThat(account.getCustomer().getName()).isEqualTo("테스트고객1");
        assertThat(account.getCustomer().getCiHash()).isEqualTo("lookup-ci-1");
    }

    @Test
    @DisplayName("계좌가 없거나 존재하지 않는 고객 조회는 다른 고객 계좌를 반환하지 않는다")
    void returnsEmptyForMissingAccounts() {
        assertThat(accountRepository.findByCustomer_CustomerId(3L)).isEmpty();
        assertThat(accountRepository.findByCustomer_CustomerId(999L)).isEmpty();
    }

    @Test
    @DisplayName("해지 계좌도 상태를 보존해 조회하고 거래 가능 여부는 서비스에서 판단한다")
    void preservesClosedStatusAndNullableValues() {
        AccountEntity account = accountRepository.findByCustomer_CustomerId(2L).orElseThrow();

        assertThat(account.getStatus()).isEqualTo(Status.CLOSED);
        assertThat(account.getStatus().canTrade()).isFalse();
        assertThat(account.getBenefit()).isNull();
        assertThat(account.getLastDomesticTradeId()).isNull();
    }

    @Test
    @DisplayName("계좌 금액을 BigDecimal로 정확하게 읽고 기존 상태와 혜택 유형을 재사용한다")
    void mapsAccountAmountsAndTypes() {
        AccountEntity account = accountRepository.findByCustomer_CustomerId(1L).orElseThrow();

        assertThat(account.getLimitAmount()).isEqualByComparingTo("12345678");
        assertThat(account.getAmount()).isEqualByComparingTo("987654321012345");
        assertThat(account.getStatus()).isEqualTo(Status.OPENED);
        assertThat(account.getBenefit()).isEqualTo(BenefitType.POSSIBLE);
        assertThat(account.getAccountNo()).isEqualTo("0000000010");
        assertThat(account.getOpenedAt()).hasToString("2026-10-20T10:30");
    }

    @Test
    @DisplayName("지정 종목만 반환하고 동일 티커의 다른 종목을 섞지 않는다")
    void restrictsLookupToRequestedProductIds() {
        List<ForeignProductEntity> products =
                foreignProductRepository
                        .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
                                List.of(2L, 1L));

        assertThat(products)
                .extracting(ForeignProductEntity::getForeignProductId)
                .containsExactly(1L, 2L);
        assertThat(products.get(0).getForeignProductType())
                .isEqualTo(ForeignProductType.FOREIGN_STOCK);
        assertThat(products.get(1).getForeignProductType()).isEqualTo(ForeignProductType.ETF);
    }

    @Test
    @DisplayName("존재하지 않는 종목 ID를 조회해도 전체 종목으로 대체하지 않는다")
    void returnsEmptyForUnknownProducts() {
        assertThat(
                        foreignProductRepository
                                .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
                                        List.of(999L)))
                .isEmpty();
    }

    @Test
    @DisplayName("적격 종목 ID 집합이 비어 있으면 종목도 반환하지 않는다")
    void returnsEmptyForEmptyProductIds() {
        assertThat(
                        foreignProductRepository
                                .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
                                        List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("동일 티커는 ID 순서로 정렬하고 입력 ID 중복과 nullable 통화를 처리한다")
    void ordersSameTickerProductsDeterministically() {
        List<ForeignProductEntity> products =
                foreignProductRepository
                        .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
                                List.of(3L, 1L, 3L));

        assertThat(products)
                .extracting(ForeignProductEntity::getForeignProductId)
                .containsExactly(1L, 3L);
        assertThat(products.get(1).getForeignProductType()).isEqualTo(ForeignProductType.ETN);
        assertThat(products.get(1).getCurrency()).isNull();
    }
}
