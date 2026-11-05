package com.app.maria.domain.sellorder.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
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

class CustomerSellOrderMapperTest {

    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;
    private SqlSession session;
    private CustomerSellOrderMapper mapper;

    @BeforeAll
    static void configureMyBatis() throws Exception {
        dataSource =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:customer_sell_order_mapper_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        Configuration configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CustomerSellOrderMapper.class);
        String xmlPath = "mappers/sellorder/CustomerSellOrderMapper.xml";
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
            s.execute("DROP TABLE IF EXISTS sell_order");
            s.execute("DROP TABLE IF EXISTS foreign_product");
            s.execute(
                    "CREATE TABLE foreign_product (foreign_product_id BIGINT PRIMARY KEY,"
                            + " ticker VARCHAR(20), name VARCHAR(100))");
            s.execute(
                    "CREATE TABLE sell_order (order_id BIGINT PRIMARY KEY, inbound_detail_id BIGINT,"
                            + " account_id BIGINT NOT NULL, foreign_product_id BIGINT NOT NULL,"
                            + " sell_qty DECIMAL(15,4) NOT NULL, base_price DECIMAL(15,4) NOT NULL,"
                            + " status VARCHAR(20) NOT NULL, processed_at TIMESTAMP NOT NULL)");
            s.execute("INSERT INTO foreign_product VALUES (1, 'AAPL', '애플')");
            // 계좌 1: 체결(오래됨) · 거부(lot 연결 전이라 inbound_detail_id NULL) · 체결(최신, 소수 금액).
            // 계좌 2 한 건은 섞이면 안 된다. 운영처럼 접수(RECEIVED) 행은 저장되지 않는다.
            s.execute(
                    "INSERT INTO sell_order VALUES"
                            + " (10, 501, 1, 1, 5, 200000.5, 'EXECUTED', '2026-03-02 10:00:00'),"
                            + " (11, NULL, 1, 1, 2, 200000, 'REJECTED', '2026-03-05 10:00:00'),"
                            + " (12, 503, 1, 1, 3, 100.3333, 'EXECUTED', '2026-03-09 10:00:00'),"
                            + " (13, 504, 2, 1, 1, 300, 'EXECUTED', '2026-03-10 10:00:00')");
        }
        session = factory.openSession(true);
        mapper = session.getMapper(CustomerSellOrderMapper.class);
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
    void returnsOnlyOwnOrdersNewestFirst() {
        List<CustomerSellOrderResponseDTO> result = mapper.selectByAccountId(1L, 0, 10);

        assertThat(result)
                .extracting(CustomerSellOrderResponseDTO::getOrderId)
                .containsExactly(12L, 11L, 10L);
    }

    @Test
    void executedOrderCarriesProductAmountAndProcessedTime() {
        CustomerSellOrderResponseDTO executed = mapper.selectByAccountId(1L, 0, 10).get(2);

        assertThat(executed.getTicker()).isEqualTo("AAPL");
        assertThat(executed.getName()).isEqualTo("애플");
        assertThat(executed.getInboundDetailId()).isEqualTo(501L);
        assertThat(executed.getSellQty()).isEqualByComparingTo("5");
        assertThat(executed.getBasePrice()).isEqualByComparingTo("200000.5");
        assertThat(executed.getOrderAmount()).isEqualByComparingTo("1000002.5");
        assertThat(executed.getStatus()).isEqualTo(SellOrderStatus.EXECUTED);
        assertThat(executed.getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 3, 2, 10, 0));
    }

    @Test
    void orderAmountIsRoundedToTwoDecimals() {
        // 3 × 100.3333 = 300.9999 → 301.00
        CustomerSellOrderResponseDTO latest = mapper.selectByAccountId(1L, 0, 1).get(0);

        assertThat(latest.getOrderAmount()).isEqualTo(new java.math.BigDecimal("301.00"));
    }

    @Test
    void rejectedOrderHasNoLot() {
        CustomerSellOrderResponseDTO rejected = mapper.selectByAccountId(1L, 0, 10).get(1);

        assertThat(rejected.getStatus()).isEqualTo(SellOrderStatus.REJECTED);
        assertThat(rejected.getInboundDetailId()).isNull();
        assertThat(rejected.getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 3, 5, 10, 0));
    }

    @Test
    void pagesWithOffsetAndCountsOnlyOwnRows() {
        assertThat(mapper.selectByAccountId(1L, 1L, 1))
                .extracting(CustomerSellOrderResponseDTO::getOrderId)
                .containsExactly(11L);
        assertThat(mapper.selectByAccountId(1L, (long) Integer.MAX_VALUE * 100, 100)).isEmpty();
        assertThat(mapper.countByAccountId(1L)).isEqualTo(3);
        assertThat(mapper.countByAccountId(2L)).isEqualTo(1);
    }
}
