package com.app.maria.domain.withdrawal.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;

class WithdrawalMapperTest {

    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;
    private SqlSession session;
    private WithdrawalMapper mapper;

    @BeforeAll
    static void configureMyBatis() throws Exception {
        dataSource =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:withdrawal_mapper_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        Configuration configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WithdrawalMapper.class);
        try (InputStream xml =
                Resources.getResourceAsStream("mappers/withdrawal/WithdrawalMapper.xml")) {
            new XMLMapperBuilder(
                            xml,
                            configuration,
                            "mappers/withdrawal/WithdrawalMapper.xml",
                            configuration.getSqlFragments())
                    .parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() throws Exception {
        resetSchemaAndData();
        session = factory.openSession(true);
        mapper = session.getMapper(WithdrawalMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (session != null) session.close();
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void selectsOnlyAvailableSourcesInFinalAtAndExchangeIdOrder() {
        List<LeftAmountDTO> result = mapper.selectAvailableLeftAmountsByAccountId(1L);

        assertThat(result)
                .extracting(LeftAmountDTO::getLeftAmountId)
                .containsExactly(102L, 106L, 101L);
        assertThat(result).extracting(LeftAmountDTO::getExchangeId).containsExactly(2L, 6L, 1L);
        assertThat(result)
                .extracting(LeftAmountDTO::getCurAmount)
                .containsExactly(
                        new BigDecimal("200"), new BigDecimal("50"), new BigDecimal("100"));
        assertThat(result).allSatisfy(value -> assertThat(value.getFinalAt()).isNotNull());
    }

    @Test
    void sumsOnlyImmatureAllocationsForRequestedWithdrawal() {
        assertThat(mapper.selectImmatureAllocatedAmountByWithdrawalId(10L))
                .isEqualByComparingTo("400");
        assertThat(mapper.selectImmatureAllocatedAmountByWithdrawalId(99L)).isZero();
    }

    @Test
    void withdrawalHistoriesAreFilteredAndNewestFirstWithAllocationTotals() {
        List<WithdrawalHistoryDTO> result =
                mapper.selectWithdrawalHistories(WithdrawalStatus.COMPLETED);

        assertThat(result)
                .extracting(WithdrawalHistoryDTO::getWithdrawalId)
                .containsExactly(11L, 10L);
        assertThat(result.get(1).getCustomerName()).isEqualTo("인출 고객");
        assertThat(result.get(1).getRiaAccountNo()).isEqualTo("1234567890");
        assertThat(result.get(1).getEarningsAmount()).isEqualByComparingTo("100");
        assertThat(result.get(1).getMaturedPrincipalAmount()).isEqualByComparingTo("300");
        assertThat(result.get(1).getImmaturePrincipalAmount()).isEqualByComparingTo("400");
        assertThat(result.get(1).getAllocationCount()).isEqualTo(4);
        assertThat(result.get(1).getNormalAllocationCount()).isEqualTo(2);
        assertThat(result.get(1).getEarlyAllocationCount()).isEqualTo(2);
    }

    @Test
    void accountWithdrawalHistoriesContainEveryStatusAndRemainNewestFirst() {
        List<WithdrawalHistoryDTO> result = mapper.selectWithdrawalHistoriesByAccountId(1L);

        assertThat(result)
                .extracting(WithdrawalHistoryDTO::getWithdrawalId)
                .containsExactly(11L, 10L, 12L);
        assertThat(result)
                .extracting(WithdrawalHistoryDTO::getStatus)
                .containsExactly(
                        WithdrawalStatus.COMPLETED,
                        WithdrawalStatus.COMPLETED,
                        WithdrawalStatus.FAILED);
    }

    @Test
    void withdrawalDetailAllocationsKeepAccountingOrderAndFinalAt() {
        WithdrawalHistoryDTO history = mapper.selectWithdrawalHistoryById(10L).orElseThrow();
        List<WithdrawalAllocationHistoryDTO> allocations =
                mapper.selectAllocationHistoriesByWithdrawalId(10L);

        assertThat(history.getRequestedAmount()).isEqualByComparingTo("800");
        assertThat(allocations)
                .extracting(WithdrawalAllocationHistoryDTO::getAllocationId)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(allocations.get(0).getFinalAt()).isNull();
        assertThat(allocations.get(0).getProductName()).isNull();
        assertThat(allocations.get(2).getFinalAt()).isNotNull();
        assertThat(allocations.get(1).getProductName()).isEqualTo("Apple");
        assertThat(allocations.get(1).getTicker()).isEqualTo("AAPL");
        assertThat(allocations.get(2).getProductName()).isEqualTo("Microsoft");
        assertThat(allocations.get(2).getTicker()).isEqualTo("MSFT");
    }

    @Test
    void failedWithdrawalIsPersistedAndQueriedWithoutAllocations() {
        WithdrawalDTO failedWithdrawal =
                WithdrawalDTO.builder()
                        .accountId(1L)
                        .requestedAmount(new BigDecimal("1000"))
                        .processedAt(LocalDateTime.of(2026, 8, 3, 9, 0))
                        .destinationAccountNo("111122223333")
                        .destinationGeneralAccountId(20L)
                        .status(WithdrawalStatus.FAILED)
                        .build();

        assertThat(mapper.insertWithdrawal(failedWithdrawal)).isEqualTo(1);

        WithdrawalHistoryDTO saved =
                mapper.selectWithdrawalHistoryById(failedWithdrawal.getWithdrawalId())
                        .orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(saved.getAllocationCount()).isZero();
    }

    private static void resetSchemaAndData() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE customer (
                        customer_id BIGINT PRIMARY KEY,
                        name VARCHAR(50) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE account (
                        account_id BIGINT PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        account_no VARCHAR(30) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE withdrawal (
                        withdrawal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        account_id BIGINT NOT NULL,
                        requested_amount DECIMAL(15, 2) NOT NULL,
                        processed_at DATETIME NOT NULL,
                        destination_account_no VARCHAR(30) NOT NULL,
                        destination_general_account_id BIGINT NOT NULL,
                        status VARCHAR(12) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE krw_exchange (
                        exchange_id BIGINT PRIMARY KEY,
                        account_id BIGINT NOT NULL,
                        order_id BIGINT,
                        settlement_status VARCHAR(12) NOT NULL,
                        final_at DATETIME NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE sell_order (
                        order_id BIGINT PRIMARY KEY,
                        foreign_product_id BIGINT NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE foreign_product (
                        foreign_product_id BIGINT PRIMARY KEY,
                        ticker VARCHAR(20) NOT NULL,
                        name VARCHAR(100) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE left_amount (
                        left_amount_id BIGINT PRIMARY KEY,
                        exchange_id BIGINT NOT NULL,
                        cur_amount DECIMAL(15, 0) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE withdrawal_allocation (
                        allocation_id BIGINT PRIMARY KEY,
                        withdrawal_id BIGINT NOT NULL,
                        left_amount_id BIGINT NULL,
                        allocated_amount DECIMAL(15, 0) NOT NULL,
                        withdrawal_at DATETIME NOT NULL,
                        type VARCHAR(40) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    INSERT INTO customer VALUES (10, '인출 고객')
                    """);
            statement.execute(
                    """
                    INSERT INTO account VALUES (1, 10, '1234567890')
                    """);
            statement.execute(
                    """
                    INSERT INTO withdrawal VALUES
                        (10, 1, 800, TIMESTAMP '2026-08-01 09:00:00', '111122223333', 20, 'COMPLETED'),
                        (11, 1, 999, TIMESTAMP '2026-08-02 09:00:00', '111122223333', 20, 'COMPLETED'),
                        (12, 1, 50, TIMESTAMP '2026-07-01 09:00:00', '111122223333', 20, 'FAILED')
                    """);
            statement.execute(
                    """
                    INSERT INTO krw_exchange VALUES
                        (1, 1, 201, 'FINALIZED', TIMESTAMP '2025-01-02 09:00:00'),
                        (2, 1, 202, 'FINALIZED', TIMESTAMP '2025-01-01 09:00:00'),
                        (3, 2, 203, 'FINALIZED', TIMESTAMP '2024-01-01 09:00:00'),
                        (4, 1, 204, 'PROVISIONAL', TIMESTAMP '2024-01-01 09:00:00'),
                        (5, 1, 205, 'FINALIZED', NULL),
                        (6, 1, 206, 'FINALIZED', TIMESTAMP '2025-01-01 09:00:00'),
                        (7, 1, 207, 'FINALIZED', TIMESTAMP '2024-01-01 09:00:00')
                    """);
            statement.execute(
                    """
                    INSERT INTO foreign_product VALUES
                        (301, 'AAPL', 'Apple'), (302, 'MSFT', 'Microsoft')
                    """);
            statement.execute(
                    """
                    INSERT INTO sell_order VALUES
                        (201, 301), (202, 302), (203, 301), (204, 301),
                        (205, 301), (206, 302), (207, 301)
                    """);
            statement.execute(
                    """
                    INSERT INTO left_amount VALUES
                        (101, 1, 100), (102, 2, 200), (103, 3, 300),
                        (104, 4, 400), (105, 5, 500), (106, 6, 50),
                        (107, 7, 0)
                    """);
            statement.execute(
                    """
                    INSERT INTO withdrawal_allocation VALUES
                        (1, 10, NULL, 100, TIMESTAMP '2026-08-01 09:00:00', 'EARNINGS_ONLY'),
                        (2, 10, 101, 300, TIMESTAMP '2026-08-01 09:00:00', 'MATURED_PRINCIPAL_INCLUDED'),
                        (3, 10, 102, 250, TIMESTAMP '2026-08-01 09:00:00', 'IMMATURE_PRINCIPAL_INCLUDED'),
                        (4, 10, 106, 150, TIMESTAMP '2026-08-01 09:00:00', 'IMMATURE_PRINCIPAL_INCLUDED'),
                        (5, 11, 103, 999, TIMESTAMP '2026-08-01 09:00:00', 'IMMATURE_PRINCIPAL_INCLUDED')
                    """);
        }
    }
}
