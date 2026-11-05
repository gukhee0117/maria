package com.app.maria.domain.domestic.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.type.Type;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;
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

class DomesticProductMapperTest {

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private DomesticProductMapper domesticProductMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader =
                Resources.getResourceAsReader("mybatis-domesticproduct-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource =
                (PooledDataSource)
                        sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        resetSchema();
        sqlSession = sqlSessionFactory.openSession(true);
        domesticProductMapper = sqlSession.getMapper(DomesticProductMapper.class);
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
    @DisplayName("FUND 종목을 저장한 대로 모든 필드가 정확히 조회된다")
    void selectByIdReturnsAllFieldsExactlyForFund() throws SQLException {
        insertProduct(
                1L,
                "448630",
                "TIGER 미국배당다우존스",
                "KOSPI",
                "FUND",
                "85.50",
                LocalDate.of(2023, 5, 10));

        Optional<DomesticProductDTO> result = domesticProductMapper.selectById(1L);

        assertThat(result).isPresent();
        DomesticProductDTO product = result.get();
        assertThat(product.getDomesticProductId()).isEqualTo(1L);
        assertThat(product.getTicker()).isEqualTo("448630");
        assertThat(product.getName()).isEqualTo("TIGER 미국배당다우존스");
        assertThat(product.getMarket()).isEqualTo("KOSPI");
        assertThat(product.getType()).isEqualTo(Type.FUND);
        assertThat(product.getDomesticStockRatio()).isEqualByComparingTo("85.50");
        assertThat(product.getInceptionDate()).isEqualTo(LocalDate.of(2023, 5, 10));
    }

    @Test
    @DisplayName("STOCK 종목은 domestic_stock_ratio/inception_date가 null로 조회된다")
    void selectByIdReturnsNullRatioAndInceptionForStock() throws SQLException {
        insertProduct(2L, "005930", "삼성전자", "KOSPI", "STOCK", null, null);

        Optional<DomesticProductDTO> result = domesticProductMapper.selectById(2L);

        assertThat(result).isPresent();
        assertThat(result.get().getType()).isEqualTo(Type.STOCK);
        assertThat(result.get().getDomesticStockRatio()).isNull();
        assertThat(result.get().getInceptionDate()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 domesticProductId는 빈 Optional을 반환한다")
    void selectByIdReturnsEmptyWhenNotFound() {
        Optional<DomesticProductDTO> result = domesticProductMapper.selectById(999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ticker 여러 개로 종목을 한꺼번에 조회하고 마스터에 없는 ticker는 결과에서 빠진다")
    void selectByTickersReturnsOnlyKnownTickers() throws SQLException {
        insertProduct(1L, "005930", "삼성전자", "KOSPI", "STOCK", null, null);
        insertProduct(2L, "000660", "SK하이닉스", "KOSPI", "STOCK", null, null);
        insertProduct(3L, "035420", "NAVER", "KOSPI", "STOCK", null, null);

        java.util.List<DomesticProductDTO> result =
                domesticProductMapper.selectByTickers(
                        java.util.Set.of("005930", "035420", "999999"));

        assertThat(result)
                .extracting(DomesticProductDTO::getTicker)
                .containsExactlyInAnyOrder("005930", "035420");
        assertThat(result)
                .extracting(DomesticProductDTO::getName)
                .containsExactlyInAnyOrder("삼성전자", "NAVER");
    }

    @Test
    @DisplayName("같은 ticker가 둘이면 domestic_product_id 순으로 돌려줘 서비스의 첫 값이 항상 같다")
    void selectByTickersOrdersByProductId() throws SQLException {
        insertProduct(9L, "005930", "나중 행", "KOSPI", "STOCK", null, null);
        insertProduct(4L, "005930", "먼저 행", "KOSPI", "STOCK", null, null);

        java.util.List<DomesticProductDTO> result =
                domesticProductMapper.selectByTickers(java.util.Set.of("005930"));

        assertThat(result).extracting(DomesticProductDTO::getName).containsExactly("먼저 행", "나중 행");
    }

    private void insertProduct(
            Long id,
            String ticker,
            String name,
            String market,
            String type,
            String domesticStockRatio,
            LocalDate inceptionDate)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            String ratioValue =
                    domesticStockRatio == null ? "NULL" : "'" + domesticStockRatio + "'";
            String inceptionValue = inceptionDate == null ? "NULL" : "'" + inceptionDate + "'";
            statement.execute(
                    "INSERT INTO domestic_product "
                            + "(domestic_product_id, ticker, name, market, type, domestic_stock_ratio, inception_date) "
                            + "VALUES ("
                            + id
                            + ", '"
                            + ticker
                            + "', '"
                            + name
                            + "', '"
                            + market
                            + "', '"
                            + type
                            + "', "
                            + ratioValue
                            + ", "
                            + inceptionValue
                            + ")");
        }
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE domestic_product (
                        domestic_product_id BIGINT PRIMARY KEY,
                        ticker VARCHAR(20) NOT NULL,
                        name VARCHAR(100) NOT NULL,
                        market VARCHAR(50),
                        type VARCHAR(15) NOT NULL,
                        domestic_stock_ratio DECIMAL(5,2),
                        inception_date DATE
                    )
                    """);
        }
    }
}
