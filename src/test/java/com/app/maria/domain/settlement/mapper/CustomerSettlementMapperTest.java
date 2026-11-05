package com.app.maria.domain.settlement.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import com.app.maria.domain.settlement.type.SettlementStatus;
import java.io.InputStream;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerSettlementMapperTest {

    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;
    private SqlSession session;
    private CustomerSettlementMapper mapper;

    @BeforeAll
    static void configureMyBatis() throws Exception {
        dataSource =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:customer_settlement_mapper_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        Configuration configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CustomerSettlementMapper.class);
        String xmlPath = "mappers/settlement/CustomerSettlementMapper.xml";
        try (InputStream xml = Resources.getResourceAsStream(xmlPath)) {
            new XMLMapperBuilder(xml, configuration, xmlPath, configuration.getSqlFragments())
                    .parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS krw_exchange");
            s.execute("DROP TABLE IF EXISTS sell_order");
            s.execute("DROP TABLE IF EXISTS foreign_product");
            s.execute(
                    "CREATE TABLE foreign_product (foreign_product_id BIGINT PRIMARY KEY,"
                            + " ticker VARCHAR(20), name VARCHAR(100))");
            s.execute(
                    "CREATE TABLE sell_order (order_id BIGINT PRIMARY KEY,"
                            + " foreign_product_id BIGINT, sell_qty DECIMAL(18,4))");
            s.execute(
                    "CREATE TABLE krw_exchange (exchange_id BIGINT PRIMARY KEY, account_id BIGINT,"
                            + " order_id BIGINT, provisional_amount DECIMAL(15,2),"
                            + " provisional_at TIMESTAMP, final_amount DECIMAL(15,2),"
                            + " final_at TIMESTAMP, settlement_status VARCHAR(20))");
            s.execute("INSERT INTO foreign_product VALUES (1, 'AAPL', '애플')");
            // 13번 주문은 종목이 없다(조인 경계). 99번 주문은 sell_order 행 자체가 없다.
            s.execute(
                    "INSERT INTO sell_order VALUES (10, 1, 5), (11, 1, 2), (12, 1, 3),"
                            + " (13, NULL, 1), (14, 1, 4)");
            // 계좌 1: 확정산(오래됨) · 가정산(중간) · 가정산(최신). 가정산도 운영처럼 final_at(확정 예정일)이 이미 있다.
            // 계좌 2 한 건은 섞이면 안 된다. 주문마다 정산은 하나(order_id UNIQUE).
            s.execute(
                    "INSERT INTO krw_exchange VALUES"
                            + " (1, 1, 10, 990, '2026-03-02 10:00:00', 1000, '2026-03-04 00:00:00', 'FINALIZED'),"
                            + " (2, 1, 11, 495, '2026-03-05 10:00:00', NULL, '2026-03-09 00:00:00', 'PROVISIONAL'),"
                            + " (3, 1, 12, 297, '2026-03-09 10:00:00', NULL, '2026-03-11 00:00:00', 'PROVISIONAL'),"
                            + " (4, 2, 14, 100, '2026-03-10 10:00:00', NULL, '2026-03-12 00:00:00', 'PROVISIONAL')");
        }
        session = factory.openSession(true);
        mapper = session.getMapper(CustomerSettlementMapper.class);
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
    void returnsOnlyOwnSettlementsNewestFirstWithProductAndAmounts() {
        List<CustomerSettlementResponseDTO> result = mapper.selectByAccountId(1L, 0, 10);

        assertThat(result)
                .extracting(CustomerSettlementResponseDTO::getExchangeId)
                .containsExactly(3L, 2L, 1L);
        CustomerSettlementResponseDTO finalized = result.get(2);
        assertThat(finalized.getTicker()).isEqualTo("AAPL");
        assertThat(finalized.getName()).isEqualTo("애플");
        assertThat(finalized.getSellQty()).isEqualByComparingTo("5");
        assertThat(finalized.getProvisionalAmount()).isEqualByComparingTo("990");
        assertThat(finalized.getFinalAmount()).isEqualByComparingTo("1000");
        assertThat(finalized.getFinalAt()).isNotNull();
        assertThat(finalized.getSettlementStatus()).isEqualTo(SettlementStatus.FINALIZED);
    }

    @Test
    void provisionalSettlementHasExpectedFinalDateButNoFinalAmount() {
        CustomerSettlementResponseDTO latest = mapper.selectByAccountId(1L, 0, 1).get(0);

        assertThat(latest.getSettlementStatus()).isEqualTo(SettlementStatus.PROVISIONAL);
        assertThat(latest.getFinalAmount()).isNull();
        assertThat(latest.getFinalAt()).isEqualTo(LocalDateTime.of(2026, 3, 11, 0, 0));
    }

    @Test
    void settlementWithoutSellOrderOrProductStillListedAndCounted() throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO krw_exchange VALUES (5, 1, 99, 50, '2026-03-12 10:00:00', NULL,"
                            + " '2026-03-16 00:00:00', 'PROVISIONAL'),"
                            + " (6, 1, 13, 60, '2026-03-13 10:00:00', NULL, '2026-03-17 00:00:00',"
                            + " 'PROVISIONAL')");
        }

        List<CustomerSettlementResponseDTO> result = mapper.selectByAccountId(1L, 0, 10);

        assertThat(result).hasSize(mapper.countByAccountId(1L));
        assertThat(result)
                .extracting(CustomerSettlementResponseDTO::getExchangeId)
                .containsExactly(6L, 5L, 3L, 2L, 1L);
        assertThat(result.get(0).getTicker()).isNull();
        assertThat(result.get(1).getSellQty()).isNull();
    }

    @Test
    void hugeOffsetReturnsEmptyPageInsteadOfFailing() {
        assertThat(mapper.selectByAccountId(1L, (long) Integer.MAX_VALUE * 100, 100)).isEmpty();
    }

    @Test
    void pagesWithOffsetAndCountsOnlyOwnRows() {
        assertThat(mapper.selectByAccountId(1L, 1, 1))
                .extracting(CustomerSettlementResponseDTO::getExchangeId)
                .containsExactly(2L);
        assertThat(mapper.countByAccountId(1L)).isEqualTo(3);
        assertThat(mapper.countByAccountId(2L)).isEqualTo(1);
        assertThat(mapper.countByAccountId(99L)).isZero();
    }
}
