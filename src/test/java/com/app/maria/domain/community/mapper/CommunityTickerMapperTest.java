package com.app.maria.domain.community.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommunityTickerMapperTest {
    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private CommunityTickerMapper mapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-community-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource =
                (PooledDataSource)
                        sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS domestic_product");
            statement.execute("DROP TABLE IF EXISTS foreign_product");
            statement.execute(
                    "CREATE TABLE domestic_product (domestic_product_id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + " ticker VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL)");
            statement.execute(
                    "CREATE TABLE foreign_product (foreign_product_id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + " ticker VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL)");
            statement.execute(
                    "INSERT INTO domestic_product (ticker, name) VALUES ('005930','삼성전자'),"
                            + " ('000660','SK하이닉스')");
            statement.execute(
                    "INSERT INTO foreign_product (ticker, name) VALUES ('NVDA','엔비디아'),"
                            + " ('AAPL','애플')");
        }
        sqlSession = sqlSessionFactory.openSession(true);
        mapper = sqlSession.getMapper(CommunityTickerMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    @DisplayName("전체 종목은 국내가 먼저, 같은 시장 안에서는 종목코드 순이다")
    void selectAll() {
        List<TickerResponseDTO> all = mapper.selectAllTickers();

        assertThat(all)
                .extracting(t -> t.getMarket() + ":" + t.getTicker())
                .containsExactly(
                        "DOMESTIC:000660", "DOMESTIC:005930", "FOREIGN:AAPL", "FOREIGN:NVDA");
        assertThat(all.get(0).getName()).isEqualTo("SK하이닉스");
    }

    @Test
    @DisplayName("국내·해외 어느 쪽에 있어도 존재하고, 없는 코드나 SQL 조각은 존재하지 않는다")
    void exists() {
        assertThat(mapper.existsTicker("005930")).isTrue();
        assertThat(mapper.existsTicker("NVDA")).isTrue();
        assertThat(mapper.existsTicker("999999")).isFalse();
        assertThat(mapper.existsTicker("' OR '1'='1")).isFalse();
    }

    @Test
    @DisplayName("코드 목록으로 이름을 찾고 없는 코드는 결과에서 빠진다")
    void selectByCodes() {
        List<TickerResponseDTO> found =
                mapper.selectTickersByCodes(List.of("005930", "NVDA", "NOPE"));

        assertThat(found)
                .extracting(TickerResponseDTO::getTicker)
                .containsExactlyInAnyOrder("005930", "NVDA");
    }
}
