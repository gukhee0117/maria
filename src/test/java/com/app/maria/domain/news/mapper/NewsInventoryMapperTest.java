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

    @BeforeEach
    void setUp() throws Exception {
        String url =
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
        mapper =
                new SqlSessionFactoryBuilder()
                        .build(config)
                        .openSession(true)
                        .getMapper(NewsInventoryMapper.class);
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
}
