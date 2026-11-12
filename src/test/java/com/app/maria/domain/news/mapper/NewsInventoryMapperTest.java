package com.app.maria.domain.news.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.news.dto.NewsHoldingRow;
import com.app.maria.domain.news.dto.NewsThemeLink;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the real news inventory SQL against a small H2 schema matching the production columns. */
class NewsInventoryMapperTest {
    private NewsInventoryMapper mapper;
    private org.apache.ibatis.session.SqlSessionFactory sessions;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        url =
                "jdbc:h2:mem:news_inventory_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE account (account_id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL)");
                statement.execute(
                        "CREATE TABLE domestic_product (domestic_product_id BIGINT PRIMARY KEY, ticker VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL, type VARCHAR(15) NOT NULL, sector_name VARCHAR(50))");
                statement.execute(
                        "CREATE TABLE domestic_stock_balance (account_id BIGINT NOT NULL, domestic_product_id BIGINT NOT NULL, qty DECIMAL(15,4) NOT NULL, status VARCHAR(20) NOT NULL)");
                statement.execute(
                        "CREATE TABLE stock_theme (stock_theme_id BIGINT PRIMARY KEY, name VARCHAR(30) NOT NULL)");
                statement.execute(
                        "CREATE TABLE domestic_product_theme (domestic_product_id BIGINT NOT NULL, stock_theme_id BIGINT NOT NULL)");
                statement.execute(
                        "CREATE TABLE domestic_order (domestic_order_id BIGINT PRIMARY KEY, account_id BIGINT, domestic_product_id BIGINT, side VARCHAR(4), qty DECIMAL(15,4), status VARCHAR(10), processed_at TIMESTAMP DEFAULT '2026-11-02 09:00:00')");
                statement.execute("INSERT INTO account VALUES (1, 101), (2, 102), (3, 103)");
                statement.execute(
                        "INSERT INTO domestic_product VALUES (1, '005930', 'Samsung', 'STOCK', 'Semiconductor'), (2, '000660', 'SK hynix', 'STOCK', 'Semiconductor'), (3, '900001', 'Fund', 'FUND', NULL), (4, '111111', 'No longer held', 'STOCK', 'Other')");
                statement.execute(
                        "INSERT INTO domestic_stock_balance VALUES (1, 1, 2, 'HOLDING'), (2, 1, 1, 'PARTIALLY_SOLD'), (3, 2, 0, 'SOLD_OUT'), (1, 3, 5, 'HOLDING'), (1, 4, 4, 'TERMINATED')");
                statement.execute("INSERT INTO stock_theme VALUES (1, 'Semiconductor')");
                statement.execute("INSERT INTO domestic_product_theme VALUES (1, 1), (2, 1)");
            }
        }

        var config =
                new Configuration(
                        new Environment(
                                "news-inventory-test",
                                new JdbcTransactionFactory(),
                                new UnpooledDataSource("org.h2.Driver", url, "sa", "")));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/news/NewsInventoryMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
        mapper = sessions.openSession(true).getMapper(NewsInventoryMapper.class);
    }

    @Test
    void streamsOnlyEligibleHeldDomesticStocksAndLoadsThemeLinks() {
        var rows = new java.util.ArrayList<NewsHoldingRow>();
        mapper.scanHoldings(
                (ResultHandler<NewsHoldingRow>) context -> rows.add(context.getResultObject()));

        assertThat(rows).extracting(NewsHoldingRow::getTicker).containsExactly("005930", "005930");
        var themes = mapper.selectThemeLinks();
        assertThat(themes)
                .extracting(NewsThemeLink::getTicker)
                .containsExactly("000660", "005930", "111111");
        assertThat(themes)
                .extracting(NewsThemeLink::getTheme)
                .containsExactly("Semiconductor", "Semiconductor", null);
    }

    private void sql(String... statements) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private java.util.Map<String, Long> priorities(long customerId) {
        // Each read represents a new request/transaction after the fixture's committed updates.
        try (var session = sessions.openSession(true)) {
            return session
                    .getMapper(NewsInventoryMapper.class)
                    .selectHoldingThemePriorities(customerId)
                    .stream()
                    .collect(
                            java.util.stream.Collectors.toMap(
                                    x -> x.getTheme(), x -> x.getPriority()));
        }
    }

    private void history() throws Exception {
        sql(
                "UPDATE domestic_stock_balance SET qty=10 WHERE account_id=1 AND domestic_product_id=1",
                "INSERT INTO domestic_product VALUES (5,'017670','Telecom','STOCK','Telecom'),(6,'015760','Energy','STOCK','Energy')",
                "INSERT INTO stock_theme VALUES (2,'Telecom'),(3,'Energy')",
                "INSERT INTO domestic_product_theme VALUES (5,2),(6,3)",
                "INSERT INTO domestic_stock_balance VALUES (1,2,3,'HOLDING'),(1,5,5,'HOLDING'),(1,6,7,'HOLDING')",
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (10,1,1,'BUY',10,'EXECUTED'),(20,1,5,'BUY',5,'EXECUTED'),(30,1,1,'BUY',2,'EXECUTED'),(40,1,1,'SELL',2,'EXECUTED'),(50,1,2,'BUY',3,'EXECUTED'),(60,1,6,'BUY',7,'EXECUTED'),(99,1,5,'BUY',100,'REJECTED'),(1000,2,1,'BUY',1,'EXECUTED')");
    }

    @Test
    void newlyAcquiredThemeWinsWhileAdditionalStockInExistingThemeDoesNotResetIt()
            throws Exception {
        history();
        assertThat(priorities(101L))
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of("Semiconductor", 1L, "Telecom", 2L, "Energy", 6L));
        assertThat(priorities(102L))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("Semiconductor", 1L));
    }

    @Test
    void partialSellKeepsStartAndFullSellThenRepurchaseMovesThemeToTop() throws Exception {
        history();
        sql(
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (70,1,5,'SELL',2,'EXECUTED')",
                "UPDATE domestic_stock_balance SET qty=3 WHERE account_id=1 AND domestic_product_id=5");
        assertThat(priorities(101L)).containsEntry("Telecom", 2L);
        sql(
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (80,1,5,'SELL',3,'EXECUTED')",
                "UPDATE domestic_stock_balance SET qty=0,status='SOLD_OUT' WHERE account_id=1 AND domestic_product_id=5");
        assertThat(priorities(101L)).doesNotContainKey("Telecom");
        sql(
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (90,1,5,'BUY',4,'EXECUTED')",
                "UPDATE domestic_stock_balance SET qty=4,status='HOLDING' WHERE account_id=1 AND domestic_product_id=5");
        assertThat(priorities(101L)).containsEntry("Telecom", 9L);
    }

    @Test
    void legacyBalancesWithoutOrdersStayAtBaselineAndInvalidHistoryDoesNotInventRecency()
            throws Exception {
        assertThat(priorities(101L)).containsEntry("Semiconductor", 0L);
        sql(
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (10,1,1,'BUY',99,'EXECUTED')");
        assertThat(priorities(101L)).containsEntry("Semiconductor", 0L);
        assertThat(priorities(103L)).isEmpty();
    }

    @Test
    void sharedThemeIsNotNewWhenOneOfItsOtherStocksWasAlreadyHeld() throws Exception {
        history();
        sql("INSERT INTO domestic_product_theme VALUES (1,3)");
        assertThat(priorities(101L)).containsEntry("Energy", 1L);
    }

    @Test
    void terminatedStockOrdersDoNotEraseTheNewStockInTheSameTheme() throws Exception {
        history();
        sql(
                "UPDATE domestic_stock_balance SET status='TERMINATED' WHERE account_id=1 AND domestic_product_id=5",
                "INSERT INTO domestic_product VALUES (7,'030200','New telecom','STOCK','Telecom')",
                "INSERT INTO domestic_product_theme VALUES (7,2)",
                "INSERT INTO domestic_stock_balance VALUES (1,7,4,'HOLDING')",
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (70,1,7,'BUY',4,'EXECUTED')");
        assertThat(priorities(101L).get("Telecom")).isGreaterThan(priorities(101L).get("Energy"));
    }

    @Test
    void businessTimestampOverridesInsertionIdAndSameTimeUsesId() throws Exception {
        history();
        sql(
                "UPDATE domestic_order SET processed_at='2026-11-03 09:00:00' WHERE domestic_order_id=20");
        assertThat(priorities(101L).get("Telecom")).isGreaterThan(priorities(101L).get("Energy"));
        sql(
                "UPDATE domestic_order SET processed_at='2026-11-03 09:00:00' WHERE domestic_order_id=60");
        assertThat(priorities(101L).get("Energy")).isGreaterThan(priorities(101L).get("Telecom"));
        assertThat(mapper.selectLatestOrderId(101L)).isEqualTo(60L);
    }

    @Test
    void soldOutStocksRemainInHistoryWhenAnotherStockKeptTheThemeAlive() throws Exception {
        history();
        sql(
                "UPDATE domestic_stock_balance SET qty=0,status='SOLD_OUT' WHERE account_id=1 AND domestic_product_id=5",
                "INSERT INTO domestic_product VALUES (7,'030200','Other telecom','STOCK','Telecom')",
                "INSERT INTO domestic_product_theme VALUES (7,2)",
                "INSERT INTO domestic_stock_balance VALUES (1,7,2,'HOLDING')",
                "DELETE FROM domestic_order WHERE domestic_order_id=60",
                "INSERT INTO domestic_order (domestic_order_id,account_id,domestic_product_id,side,qty,status) VALUES (22,1,6,'BUY',7,'EXECUTED'),(25,1,7,'BUY',2,'EXECUTED'),(70,1,5,'SELL',5,'EXECUTED')");
        assertThat(priorities(101L).get("Telecom")).isLessThan(priorities(101L).get("Energy"));
    }

    @Test
    void impossibleBackdatedSellFallsBackInsteadOfInventingAReentry() throws Exception {
        history();
        sql(
                "UPDATE domestic_order SET processed_at='2026-11-01 09:00:00' WHERE domestic_order_id=40");
        assertThat(priorities(101L)).containsEntry("Semiconductor", 0L);
    }
}
