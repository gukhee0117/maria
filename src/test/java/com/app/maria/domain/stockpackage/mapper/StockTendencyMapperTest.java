package com.app.maria.domain.stockpackage.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 원본 DDL의 관련 테이블과 실제 XML을 H2 MySQL 모드에서 실행한다. 실 MariaDB/FK 전체 검증과는 구분한다. */
class StockTendencyMapperTest {
    private Connection connection;
    private StockTendencyMapper mapper;
    private int key;

    @BeforeEach
    void setup() throws Exception {
        String url =
                "jdbc:h2:mem:tendency_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        String ddl = Files.readString(Path.of("db/01_ria_ddl.sql"));
        for (String table : List.of("account", "domestic_product", "domestic_order")) {
            var matcher =
                    Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\) ENGINE=.*?;")
                            .matcher(ddl);
            assertThat(matcher.find()).as("원본 DDL 테이블: %s", table).isTrue();
            sql(matcher.group().replaceFirst("(?s)\\s+ENGINE=.*$", ";"));
        }
        sql(
                "INSERT INTO domestic_product (domestic_product_id,ticker,name,type,sector_name) VALUES (1,'005930','삼성전자','STOCK','전기전자'),(2,'000660','SK하이닉스','STOCK',NULL)");
        sql(
                "INSERT INTO account (account_id,customer_id,status,limit_amount) VALUES (1,1,'OPENED',1000000),(2,2,'OPENED',1000000)");

        var config =
                new Configuration(
                        new Environment(
                                "tendency-test",
                                new JdbcTransactionFactory(),
                                new UnpooledDataSource("org.h2.Driver", url, "sa", "")));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/stockpackage/StockTendencyMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        mapper =
                new SqlSessionFactoryBuilder()
                        .build(config)
                        .openSession(true)
                        .getMapper(StockTendencyMapper.class);
    }

    private void sql(String text) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(text);
        }
    }

    private void order(
            long accountId, long productId, String side, String amount, String fee, String at)
            throws Exception {
        sql(
                "INSERT INTO domestic_order (account_id,domestic_product_id,side,qty,price,amount,fee_amount,channel,idempotency_key,status,processed_at) VALUES ("
                        + accountId
                        + ","
                        + productId
                        + ",'"
                        + side
                        + "',1,100,"
                        + amount
                        + ","
                        + fee
                        + ",'CUSTOMER','k"
                        + (++key)
                        + "','EXECUTED','"
                        + at
                        + "')");
    }

    @Test
    @DisplayName("체결된 주문만 내 계좌 기준으로 종목별로 모으고 매수 금액은 수수료를 뺀다")
    void summaries() throws Exception {
        order(1, 1, "BUY", "-1000000", "1000", "2026-09-10 10:00:00");
        order(1, 1, "BUY", "-500000", "500", "2026-09-12 10:00:00");
        order(1, 1, "SELL", "300000", "300", "2026-09-20 10:00:00");
        order(1, 2, "BUY", "-200000", "200", "2026-10-01 10:00:00");
        order(2, 1, "BUY", "-9999999", "9", "2026-10-02 10:00:00"); // 다른 고객
        sql(
                "INSERT INTO domestic_order (account_id,domestic_product_id,side,qty,status,reject_reason,channel,idempotency_key,processed_at) VALUES (1,1,'BUY',1,'REJECTED','LIMIT','CUSTOMER','rej','2026-10-03 10:00:00')");

        var rows = mapper.selectTradeSummariesByCustomerId(1L);

        assertThat(rows).hasSize(2);
        var first = rows.get(0);
        assertThat(first.getDomesticProductId()).isEqualTo(1L);
        assertThat(first.getSectorName()).isEqualTo("전기전자");
        assertThat(first.getBuyAmount()).isEqualByComparingTo("1498500"); // 999000 + 499500
        assertThat(first.getBuyCount()).isEqualTo(2);
        assertThat(first.getSellCount()).isEqualTo(1);
        var second = rows.get(1);
        assertThat(second.getSectorName()).isNull();
        assertThat(second.getBuyAmount()).isEqualByComparingTo("199800");
        assertThat(second.getSellCount()).isZero();
    }

    @Test
    @DisplayName("주문이 없으면 체결 주문 수는 0이고 처음 체결 시각은 null이다")
    void periodWithoutOrders() {
        var none = mapper.selectTradePeriodByCustomerId(1L);

        assertThat(none.getOrderCount()).isZero();
        assertThat(none.getFirstProcessedAt()).isNull();
    }

    @Test
    @DisplayName("체결 주문 수와 처음 체결 시각을 내 계좌 기준으로 센다")
    void period() throws Exception {
        order(1, 1, "BUY", "-1000000", "1000", "2026-09-12 10:00:00");
        order(1, 2, "BUY", "-200000", "200", "2026-09-10 09:00:00");
        order(2, 1, "BUY", "-300000", "300", "2026-08-01 09:00:00"); // 다른 고객

        var period = mapper.selectTradePeriodByCustomerId(1L);
        assertThat(period.getOrderCount()).isEqualTo(2);
        assertThat(period.getFirstProcessedAt()).isEqualTo(LocalDateTime.of(2026, 9, 10, 9, 0));
    }
}
