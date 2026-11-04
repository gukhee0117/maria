package com.app.maria.domain.stockpackage.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.stockpackage.vo.StockCandidateProductVO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
class StockCandidateMapperTest {
    private StockCandidateMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        String url =
                "jdbc:h2:mem:candidate_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        Connection connection = DriverManager.getConnection(url, "sa", "");
        String ddl = Files.readString(Path.of("db/01_ria_ddl.sql"));
        for (String table : List.of("domestic_product", "stock_theme", "domestic_product_theme")) {
            var matcher =
                    Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\) ENGINE=.*?;")
                            .matcher(ddl);
            assertThat(matcher.find()).as("원본 DDL 테이블: %s", table).isTrue();
            try (var statement = connection.createStatement()) {
                statement.execute(matcher.group().replaceFirst("(?s)\\s+ENGINE=.*$", ";"));
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO domestic_product (domestic_product_id,ticker,name,market,type,sector_name) VALUES "
                            + "(1,'005930','삼성전자','KOSPI','STOCK','전기전자'),"
                            + "(2,'000660','SK하이닉스','KOSPI','STOCK','전기전자'),"
                            + "(3,'900020','국내고배당 ETF','KOSPI','FUND',NULL),"
                            + "(4,'111111','100%성장','KOSDAQ','STOCK',NULL),"
                            + "(5,'222222','100가나다','KOSDAQ','STOCK',NULL)");
            statement.execute(
                    "INSERT INTO stock_theme (stock_theme_id,name) VALUES (1,'반도체'),(2,'배당'),(3,'2차전지')");
            statement.execute(
                    "INSERT INTO domestic_product_theme (domestic_product_id,stock_theme_id) VALUES (1,1),(2,1),(3,2),(1,2)");
        }
        var config =
                new Configuration(
                        new Environment(
                                "candidate-test",
                                new JdbcTransactionFactory(),
                                new UnpooledDataSource("org.h2.Driver", url, "sa", "")));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/stockpackage/StockCandidateMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        mapper =
                new SqlSessionFactoryBuilder()
                        .build(config)
                        .openSession(true)
                        .getMapper(StockCandidateMapper.class);
    }

    private List<String> names(List<StockCandidateProductVO> rows) {
        return rows.stream().map(StockCandidateProductVO::getName).toList();
    }

    @Test
    @DisplayName("조건이 없으면 전체를 이름순으로 준다")
    void all() {
        var rows = mapper.selectCandidateProducts(List.of(), List.of(), null);

        assertThat(names(rows)).containsExactly("100%성장", "100가나다", "SK하이닉스", "국내고배당 ETF", "삼성전자");
        assertThat(rows.get(4).getMarket()).isEqualTo("KOSPI");
        assertThat(rows.get(4).getSectorName()).isEqualTo("전기전자");
    }

    @Test
    @DisplayName("상품군으로 거른다")
    void byType() {
        assertThat(names(mapper.selectCandidateProducts(List.of("FUND"), List.of(), null)))
                .containsExactly("국내고배당 ETF");
        assertThat(mapper.selectCandidateProducts(List.of("STOCK", "FUND"), List.of(), null))
                .hasSize(5);
    }

    @Test
    @DisplayName("테마는 하나라도 속하면 포함하고 같은 종목이 두 번 나오지 않는다")
    void byTheme() {
        assertThat(names(mapper.selectCandidateProducts(List.of(), List.of("반도체"), null)))
                .containsExactly("SK하이닉스", "삼성전자");
        // 삼성전자는 반도체·배당 둘 다에 속하지만 한 번만 나온다.
        assertThat(names(mapper.selectCandidateProducts(List.of(), List.of("반도체", "배당"), null)))
                .containsExactly("SK하이닉스", "국내고배당 ETF", "삼성전자");
        assertThat(mapper.selectCandidateProducts(List.of(), List.of("2차전지"), null)).isEmpty();
    }

    @Test
    @DisplayName("검색어는 이름이나 종목코드에 들어 있으면 맞고, 이스케이프한 %는 문자 그대로 찾는다")
    void byKeyword() {
        assertThat(names(mapper.selectCandidateProducts(List.of(), List.of(), "삼성")))
                .containsExactly("삼성전자");
        assertThat(names(mapper.selectCandidateProducts(List.of(), List.of(), "000660")))
                .containsExactly("SK하이닉스");
        // 서비스가 이스케이프한 '100\%'는 이름에 100%가 있는 종목만 찾는다.
        assertThat(names(mapper.selectCandidateProducts(List.of(), List.of(), "100\\%")))
                .containsExactly("100%성장");
    }

    @Test
    @DisplayName("조건을 함께 주면 모두 만족하는 종목만 나온다")
    void combined() {
        assertThat(names(mapper.selectCandidateProducts(List.of("STOCK"), List.of("배당"), "삼성")))
                .containsExactly("삼성전자");
    }

    @Test
    @DisplayName("테마 이름 목록은 이름순이다")
    void themeNames() {
        assertThat(mapper.selectThemeNames()).containsExactly("2차전지", "반도체", "배당");
    }
}
