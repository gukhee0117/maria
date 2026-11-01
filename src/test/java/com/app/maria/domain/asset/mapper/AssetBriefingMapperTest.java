package com.app.maria.domain.asset.mapper;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 XML을 H2 MySQL 모드에서 실행한다. SQL 필터·집계 검증이며 실 MariaDB 검증과 구분한다. */
class AssetBriefingMapperTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 30);
    private Connection connection;
    private SqlSession session;
    private AssetBriefingMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        String url =
                "jdbc:h2:mem:asset_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        sql(
                "CREATE TABLE domestic_stock_balance (account_id BIGINT, qty DECIMAL(15,4), avg_purchase_price DECIMAL(15,4), status VARCHAR(20), domestic_stock_balance_id BIGINT AUTO_INCREMENT PRIMARY KEY, domestic_product_id BIGINT DEFAULT 1)");
        sql(
                "CREATE TABLE domestic_product (domestic_product_id BIGINT PRIMARY KEY, ticker VARCHAR(20))");
        sql("INSERT INTO domestic_product VALUES (1,'005930'),(2,'000660')");
        sql(
                "CREATE TABLE krw_exchange (account_id BIGINT, final_amount DECIMAL(15,0), settlement_status VARCHAR(12), final_at TIMESTAMP)");
        sql(
                "CREATE TABLE domestic_order (account_id BIGINT, side VARCHAR(4), status VARCHAR(10), amount DECIMAL(15,0), processed_at TIMESTAMP)");
        var dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        var config =
                new Configuration(
                        new Environment("asset-test", new JdbcTransactionFactory(), dataSource));
        String resource = "mappers/asset/AssetBriefingMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(AssetBriefingMapper.class);
    }

    private void sql(String text) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(text);
        }
    }

    @AfterEach
    void close() throws Exception {
        if (session != null) session.close();
        if (connection != null) connection.close();
    }

    @Test
    void costUsesOnlyPositiveRemainingHoldingsIncludingRestrictedAndSuspended() throws Exception {
        sql(
                "INSERT INTO domestic_stock_balance (account_id,qty,avg_purchase_price,status) VALUES (11,10,100.5,'HOLDING'),(11,4,200,'PARTIALLY_SOLD'),(11,3,25.25,'TRADE_RESTRICTED'),(11,2,50,'TRADE_SUSPENDED'),(11,100,999,'SOLD_OUT'),(11,100,999,'TERMINATED'),(11,0,999,'HOLDING'),(11,-1,999,'HOLDING'),(22,100,999,'HOLDING')");
        assertThat(mapper.sumHoldingCost(11L)).isEqualByComparingTo("1980.75");
        assertThat(mapper.sumHoldingCost(999L)).isEqualByComparingTo("0");
        var holdings = mapper.selectHoldings(11L);
        assertThat(holdings)
                .hasSize(4)
                .extracting(value -> value.getTicker())
                .containsOnly("005930");
        assertThat(holdings)
                .extracting(value -> value.getQty())
                .usingComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .containsExactly(
                        new java.math.BigDecimal("10"),
                        new java.math.BigDecimal("4"),
                        new java.math.BigDecimal("3"),
                        new java.math.BigDecimal("2"));
        assertThat(mapper.selectHoldings(999L)).isEmpty();
    }

    @Test
    void inflowRequiresFinalizedAndIncludesExactClockBoundaryButNoFutureOrOtherAccount()
            throws Exception {
        sql(
                "INSERT INTO krw_exchange VALUES (11,2000,'FINALIZED','2026-10-01 12:00:00'),(11,3000,'FINALIZED','2026-11-02 10:30:00'),(11,9000,'PROVISIONAL','2026-10-01 12:00:00'),(11,8000,'FINALIZED','2026-11-02 10:30:01'),(11,7000,'FINALIZED',NULL),(22,6000,'FINALIZED','2026-10-01 12:00:00')");
        assertThat(mapper.sumFinalizedInflow(11L, NOW)).isEqualByComparingTo("5000");
        assertThat(mapper.sumFinalizedInflow(999L, NOW)).isEqualByComparingTo("0");
    }

    @Test
    void monthlyBuysGroupAcrossYearsAndExcludeSellRejectedFutureAndOtherAccounts()
            throws Exception {
        sql(
                "INSERT INTO domestic_order VALUES (11,'BUY','EXECUTED',100,'2025-11-02 09:00:00'),(11,'BUY','EXECUTED',200,'2026-10-01 09:00:00'),(11,'BUY','EXECUTED',300,'2026-10-31 23:59:59'),(11,'BUY','EXECUTED',400,'2026-11-02 10:30:00'),(11,'BUY','EXECUTED',9000,'2026-11-02 10:30:01'),(11,'SELL','EXECUTED',8000,'2026-10-01 09:00:00'),(11,'BUY','REJECTED',7000,'2026-10-01 09:00:00'),(22,'BUY','EXECUTED',6000,'2026-10-01 09:00:00')");
        var rows = mapper.selectMonthlyPurchases(11L, NOW);
        assertThat(rows).hasSize(3);
        assertThat(rows)
                .extracting(row -> row.getPurchaseYear() + "-" + row.getPurchaseMonth())
                .containsExactly("2025-11", "2026-10", "2026-11");
        assertThat(rows.get(0).getBuyAmount()).isEqualByComparingTo("100");
        assertThat(rows.get(1).getBuyAmount()).isEqualByComparingTo("500");
        assertThat(rows.get(2).getBuyAmount()).isEqualByComparingTo("400");
        assertThat(mapper.selectMonthlyPurchases(999L, NOW)).isEmpty();
    }

    @Test
    void aggregatesDoNotMultiplyWhenAccountHasSeveralHoldingsExchangesAndOrders() throws Exception {
        sql(
                "INSERT INTO domestic_stock_balance (account_id,qty,avg_purchase_price,status) VALUES (11,2,100,'HOLDING'),(11,3,200,'HOLDING')");
        sql(
                "INSERT INTO krw_exchange VALUES (11,1000,'FINALIZED','2026-10-01 09:00:00'),(11,2000,'FINALIZED','2026-10-02 09:00:00')");
        sql(
                "INSERT INTO domestic_order VALUES (11,'BUY','EXECUTED',100,'2026-10-01 09:00:00'),(11,'BUY','EXECUTED',200,'2026-10-02 09:00:00')");
        assertThat(mapper.sumHoldingCost(11L)).isEqualByComparingTo("800");
        assertThat(mapper.sumFinalizedInflow(11L, NOW)).isEqualByComparingTo("3000");
        assertThat(mapper.selectMonthlyPurchases(11L, NOW))
                .singleElement()
                .satisfies(row -> assertThat(row.getBuyAmount()).isEqualByComparingTo("300"));
    }

    @Test
    void purchaseAmountIsPositiveEvenWhenLedgerRecordsCashDebitAsNegative() throws Exception {
        sql(
                "INSERT INTO domestic_order VALUES (11,'BUY','EXECUTED',-100,'2026-10-01 09:00:00'),(11,'BUY','EXECUTED',200,'2026-10-02 09:00:00')");
        assertThat(mapper.selectMonthlyPurchases(11L, NOW))
                .singleElement()
                .satisfies(row -> assertThat(row.getBuyAmount()).isEqualByComparingTo("300"));
    }
}
