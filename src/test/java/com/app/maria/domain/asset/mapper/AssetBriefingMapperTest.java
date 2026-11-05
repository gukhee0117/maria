package com.app.maria.domain.asset.mapper;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
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

/** 원본 DDL의 관련 4개 테이블과 실제 XML을 H2 MySQL 모드에서 실행한다. 실 MariaDB/FK 전체 검증과는 구분한다. */
class AssetBriefingMapperTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 30);
    private Connection connection;
    private SqlSession session;
    private AssetBriefingMapper mapper;
    private long exchangeOrderId;

    @BeforeEach
    void setup() throws Exception {
        String url =
                "jdbc:h2:mem:asset_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        String ddl = Files.readString(Path.of("db/01_ria_ddl.sql"));
        for (String table :
                java.util.List.of(
                        "domestic_product",
                        "domestic_stock_balance",
                        "krw_exchange",
                        "domestic_order")) {
            var matcher =
                    Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\) ENGINE=.*?;")
                            .matcher(ddl);
            assertThat(matcher.find()).as("원본 DDL 테이블: %s", table).isTrue();
            // H2가 적용하지 않는 테이블 엔진·문자셋 옵션만 제거한다. 컬럼·DEFAULT·CHECK는 원본을 유지한다.
            sql(matcher.group().replaceFirst("(?s)\\s+ENGINE=.*$", ";"));
        }
        sql(
                "INSERT INTO domestic_product (domestic_product_id,ticker,name,type) VALUES (1,'005930','삼성전자','STOCK'),(2,'000660','SK하이닉스','STOCK')");
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

    private void holding(long accountId, String qty, String cost, String status) throws Exception {
        holding(accountId, 1L, qty, cost, status);
    }

    private void holding(long accountId, long productId, String qty, String cost, String status)
            throws Exception {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO domestic_stock_balance (account_id,domestic_product_id,qty,avg_purchase_price,status,last_purchase_date) VALUES (?,?,?,?,?,?)")) {
            statement.setLong(1, accountId);
            statement.setLong(2, productId);
            statement.setBigDecimal(3, new BigDecimal(qty));
            statement.setBigDecimal(4, new BigDecimal(cost));
            statement.setString(5, status);
            statement.setObject(6, NOW.minusDays(5));
            statement.executeUpdate();
        }
    }

    private void exchange(long accountId, String amount, String status, String finalAt)
            throws Exception {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO krw_exchange (account_id,order_id,provisional_amount,provisional_at,final_amount,settlement_status,final_at) VALUES (?,?,?,?,?,?,?)")) {
            statement.setLong(1, accountId);
            statement.setLong(2, ++exchangeOrderId);
            statement.setBigDecimal(3, new BigDecimal(amount));
            statement.setObject(4, NOW.minusDays(5));
            statement.setBigDecimal(5, new BigDecimal(amount));
            statement.setString(6, status);
            statement.setObject(7, finalAt == null ? null : LocalDateTime.parse(finalAt));
            statement.executeUpdate();
        }
    }

    private void order(long accountId, String side, String status, String amount, String at)
            throws Exception {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO domestic_order (account_id,domestic_product_id,side,qty,price,amount,channel,idempotency_key,status,reject_reason,processed_at) VALUES (?,1,?,1,?,?,'CUSTOMER',?,?,?,?)")) {
            statement.setLong(1, accountId);
            statement.setString(2, side);
            statement.setBigDecimal(3, new BigDecimal(amount).abs());
            statement.setBigDecimal(4, new BigDecimal(amount));
            statement.setString(5, UUID.randomUUID().toString());
            statement.setString(6, status);
            statement.setString(7, "REJECTED".equals(status) ? "TEST_REJECTED" : null);
            statement.setObject(8, LocalDateTime.parse(at));
            statement.executeUpdate();
        }
    }

    @AfterEach
    void close() throws Exception {
        if (session != null) session.close();
        if (connection != null) connection.close();
    }

    @Test
    void costUsesOnlyPositiveRemainingHoldingsIncludingRestrictedAndSuspended() throws Exception {
        holding(11, "10", "100.5", "HOLDING");
        holding(11, "4", "200", "PARTIALLY_SOLD");
        holding(11, "3", "25.25", "TRADE_RESTRICTED");
        holding(11, "2", "50", "TRADE_SUSPENDED");
        holding(11, "100", "999", "SOLD_OUT");
        holding(11, "100", "999", "TERMINATED");
        holding(11, "0", "999", "HOLDING");
        holding(11, "-1", "999", "HOLDING");
        holding(22, "100", "999", "HOLDING");
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
        exchange(11, "2000", "FINALIZED", "2026-10-01T12:00:00");
        exchange(11, "3000", "FINALIZED", "2026-11-02T10:30:00");
        exchange(11, "9000", "PROVISIONAL", "2026-10-01T12:00:00");
        exchange(11, "8000", "FINALIZED", "2026-11-02T10:30:01");
        exchange(11, "7000", "FINALIZED", null);
        exchange(22, "6000", "FINALIZED", "2026-10-01T12:00:00");
        assertThat(mapper.sumFinalizedInflow(11L, NOW)).isEqualByComparingTo("5000");
        assertThat(mapper.sumFinalizedInflow(999L, NOW)).isEqualByComparingTo("0");
    }

    @Test
    void monthlyBuysGroupAcrossYearsAndExcludeSellRejectedFutureAndOtherAccounts()
            throws Exception {
        order(11, "BUY", "EXECUTED", "100", "2025-11-02T09:00:00");
        order(11, "BUY", "EXECUTED", "200", "2026-10-01T09:00:00");
        order(11, "BUY", "EXECUTED", "300", "2026-10-31T23:59:59");
        order(11, "BUY", "EXECUTED", "400", "2026-11-02T10:30:00");
        order(11, "BUY", "EXECUTED", "9000", "2026-11-02T10:30:01");
        order(11, "SELL", "EXECUTED", "8000", "2026-10-01T09:00:00");
        order(11, "BUY", "REJECTED", "7000", "2026-10-01T09:00:00");
        order(22, "BUY", "EXECUTED", "6000", "2026-10-01T09:00:00");
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
        holding(11, "2", "100", "HOLDING");
        holding(11, "3", "200", "HOLDING");
        exchange(11, "1000", "FINALIZED", "2026-10-01T09:00:00");
        exchange(11, "2000", "FINALIZED", "2026-10-02T09:00:00");
        order(11, "BUY", "EXECUTED", "100", "2026-10-01T09:00:00");
        order(11, "BUY", "EXECUTED", "200", "2026-10-02T09:00:00");
        assertThat(mapper.sumHoldingCost(11L)).isEqualByComparingTo("800");
        assertThat(mapper.sumFinalizedInflow(11L, NOW)).isEqualByComparingTo("3000");
        assertThat(mapper.selectMonthlyPurchases(11L, NOW))
                .singleElement()
                .satisfies(row -> assertThat(row.getBuyAmount()).isEqualByComparingTo("300"));
    }

    @Test
    void purchaseAmountIsPositiveEvenWhenLedgerRecordsCashDebitAsNegative() throws Exception {
        order(11, "BUY", "EXECUTED", "-100", "2026-10-01T09:00:00");
        order(11, "BUY", "EXECUTED", "200", "2026-10-02T09:00:00");
        assertThat(mapper.selectMonthlyPurchases(11L, NOW))
                .singleElement()
                .satisfies(row -> assertThat(row.getBuyAmount()).isEqualByComparingTo("300"));
    }

    @Test
    void holdingsMapEachProductsTickerWithoutLosingLeadingZeros() throws Exception {
        holding(11, 1, "2", "100", "HOLDING");
        holding(11, 2, "3", "200", "HOLDING");
        assertThat(mapper.selectHoldings(11L))
                .extracting(value -> value.getTicker())
                .containsExactly("005930", "000660");
    }

    @Test
    void originalDdlRejectsInvalidExecutedAndRejectedOrders() {
        String insert =
                "INSERT INTO domestic_order (account_id,domestic_product_id,side,qty,price,amount,channel,idempotency_key,status,reject_reason,processed_at) VALUES ";
        assertThatThrownBy(
                        () ->
                                sql(
                                        insert
                                                + "(11,1,'BUY',1,NULL,100,'CUSTOMER','missing-price','EXECUTED',NULL,'2026-10-01 09:00:00')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                sql(
                                        insert
                                                + "(11,1,'BUY',0.5,100,100,'CUSTOMER','fractional','EXECUTED',NULL,'2026-10-01 09:00:00')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                sql(
                                        insert
                                                + "(11,1,'BUY',1,NULL,NULL,'CUSTOMER','missing-reason','REJECTED',NULL,'2026-10-01 09:00:00')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void holdingRowsCarryProductAndPositionOrderedByPurchaseAmountAndSkipSoldOutAndOtherAccounts()
            throws Exception {
        sql("UPDATE domestic_product SET sector_name = '반도체' WHERE domestic_product_id = 1");
        holding(11, 1L, "10", "100", "HOLDING"); // 매입 1,000
        holding(11, 2L, "3", "1000", "TRADE_RESTRICTED"); // 매입 3,000 (제일 큼)
        holding(11, 2L, "100", "999", "SOLD_OUT");
        holding(11, 1L, "0", "999", "HOLDING");
        holding(12, 1L, "5", "500", "HOLDING");

        var rows = mapper.selectHoldingRows(11L, AssetBriefingMapper.ACTIVE_HOLDING_STATUSES);

        assertThat(rows).extracting(row -> row.getTicker()).containsExactly("000660", "005930");
        var first = rows.get(0);
        assertThat(first.getName()).isEqualTo("SK하이닉스");
        assertThat(first.getStatus().name()).isEqualTo("TRADE_RESTRICTED");
        assertThat(first.getQty()).isEqualByComparingTo("3");
        assertThat(first.getAvgPurchasePrice()).isEqualByComparingTo("1000");
        var second = rows.get(1);
        assertThat(second.getSectorName()).isEqualTo("반도체");
        assertThat(second.getType().name()).isEqualTo("STOCK");
    }
}
