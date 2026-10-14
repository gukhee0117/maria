package com.app.maria.domain.targetproduct.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.targetproduct.dto.TargetProductJudgementDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailureListDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementListDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductSearchDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductSummaryDTO;
import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

class TargetProductMapperTest {

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private TargetProductMapper targetProductMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader =
                Resources.getResourceAsReader("mybatis-targetproduct-test-config.xml")) {
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
        targetProductMapper = sqlSession.getMapper(TargetProductMapper.class);
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

    private static TargetProductJudgementDTO.TargetProductJudgementDTOBuilder baseBuilder(
            Long mydataTradeId) {
        return TargetProductJudgementDTO.builder()
                .mydataTradeId(mydataTradeId)
                .ciHash("ci-1")
                .stockType(StockType.FOREIGN_STOCK)
                .isTarget(true)
                .tradeType(TradeType.BUY)
                .amount(new BigDecimal("1000000.00"))
                .tradeDate(LocalDate.of(2026, 3, 5))
                .netBuyAmount(new BigDecimal("1000000.00"))
                .judgedAt(LocalDateTime.of(2026, 8, 7, 3, 0));
    }

    @Test
    @DisplayName("판별 결과를 저장하면 저장된 행을 확인할 수 있다")
    void insertJudgementSavesRow() throws SQLException {
        TargetProductJudgementDTO dto =
                baseBuilder(1L)
                        .stockType(StockType.FUND)
                        .fundCode("448630")
                        .fundName("TIGER 미국배당다우존스")
                        .foreignStockRatio(new BigDecimal("72.50"))
                        .inceptionDate(LocalDate.of(2023, 5, 10))
                        .build();

        targetProductMapper.insertJudgement(dto);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                "SELECT mydata_trade_id, ci_hash, stock_type, fund_code, fund_name, is_target, foreign_stock_ratio, inception_date, "
                                        + "trade_type, amount, trade_date, net_buy_amount FROM target_product_judgement WHERE mydata_trade_id = 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("mydata_trade_id")).isEqualTo(1L);
            assertThat(resultSet.getString("ci_hash")).isEqualTo("ci-1");
            assertThat(resultSet.getString("stock_type")).isEqualTo("FUND");
            assertThat(resultSet.getString("fund_code")).isEqualTo("448630");
            assertThat(resultSet.getString("fund_name")).isEqualTo("TIGER 미국배당다우존스");
            assertThat(resultSet.getBoolean("is_target")).isTrue();
            assertThat(resultSet.getBigDecimal("foreign_stock_ratio"))
                    .isEqualByComparingTo("72.50");
            assertThat(resultSet.getDate("inception_date").toLocalDate())
                    .isEqualTo(LocalDate.of(2023, 5, 10));
            assertThat(resultSet.getString("trade_type")).isEqualTo("BUY");
            assertThat(resultSet.getBigDecimal("amount")).isEqualByComparingTo("1000000.00");
            assertThat(resultSet.getDate("trade_date").toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 3, 5));
            assertThat(resultSet.getBigDecimal("net_buy_amount"))
                    .isEqualByComparingTo("1000000.00");
        }
    }

    @Test
    @DisplayName("SELL 거래는 음수로 부호처리된 net_buy_amount가 그대로 저장된다")
    void insertJudgementSavesNegativeNetBuyAmountForSellTrade() throws SQLException {
        TargetProductJudgementDTO dto =
                baseBuilder(11L)
                        .tradeType(TradeType.SELL)
                        .amount(new BigDecimal("300000.00"))
                        .netBuyAmount(new BigDecimal("-300000.00"))
                        .build();

        targetProductMapper.insertJudgement(dto);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                "SELECT trade_type, amount, net_buy_amount FROM target_product_judgement WHERE mydata_trade_id = 11")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("trade_type")).isEqualTo("SELL");
            assertThat(resultSet.getBigDecimal("amount")).isEqualByComparingTo("300000.00");
            assertThat(resultSet.getBigDecimal("net_buy_amount"))
                    .isEqualByComparingTo("-300000.00");
        }
    }

    @Test
    @DisplayName("FOREIGN_STOCK 등 비FUND 판정은 fund_code와 비중 정보 없이 저장된다")
    void insertJudgementSavesRowWithoutFundInfoForNonFund() throws SQLException {
        TargetProductJudgementDTO dto =
                baseBuilder(2L)
                        .ticker("AAPL")
                        .fundCode(null)
                        .foreignStockRatio(null)
                        .inceptionDate(null)
                        .build();

        targetProductMapper.insertJudgement(dto);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                "SELECT stock_type, ticker, fund_code, foreign_stock_ratio FROM target_product_judgement WHERE mydata_trade_id = 2")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("stock_type")).isEqualTo("FOREIGN_STOCK");
            assertThat(resultSet.getString("ticker")).isEqualTo("AAPL");
            assertThat(resultSet.getString("fund_code")).isNull();
            assertThat(resultSet.getBigDecimal("foreign_stock_ratio")).isNull();
        }
    }

    @Test
    @DisplayName("동일 mydataTradeId는 두 번 저장할 수 없다")
    void mydataTradeIdMustBeUnique() {
        TargetProductJudgementDTO first = baseBuilder(3L).build();
        targetProductMapper.insertJudgement(first);

        TargetProductJudgementDTO duplicate = baseBuilder(3L).isTarget(false).build();

        assertThatThrownBy(() -> targetProductMapper.insertJudgement(duplicate))
                .isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
    }

    @Test
    @DisplayName("판정이 저장된 mydataTradeId는 존재 여부 조회에서 true를 반환한다")
    void existsByMydataTradeIdReturnsTrueWhenJudgementExists() {
        TargetProductJudgementDTO dto = baseBuilder(10L).build();
        targetProductMapper.insertJudgement(dto);

        boolean exists = targetProductMapper.existsByMydataTradeId(10L);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("판정이 저장되지 않은 mydataTradeId는 존재 여부 조회에서 false를 반환한다")
    void existsByMydataTradeIdReturnsFalseWhenJudgementDoesNotExist() {
        boolean exists = targetProductMapper.existsByMydataTradeId(999L);

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("판정 결과 목록을 고객명과 함께 최신순으로, size 만큼만 조회한다")
    void selectJudgementsReturnsPageOrderedByJudgedAtDesc() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(
                baseBuilder(20L).judgedAt(LocalDateTime.of(2026, 8, 7, 3, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(21L).judgedAt(LocalDateTime.of(2026, 8, 8, 3, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(22L).judgedAt(LocalDateTime.of(2026, 8, 9, 3, 0)).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder().offset(0).size(2).build());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getJudgedAt()).isEqualTo(LocalDateTime.of(2026, 8, 9, 3, 0));
        assertThat(result.get(1).getJudgedAt()).isEqualTo(LocalDateTime.of(2026, 8, 8, 3, 0));
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
        assertThat(result.get(0).getStockType()).isEqualTo(StockType.FOREIGN_STOCK);
        assertThat(result.get(0).getTradeType()).isEqualTo(TradeType.BUY);
    }

    @Test
    @DisplayName("판정 목록에 해외주식비중·설정일 판단기준 값을 함께 반환한다")
    void selectJudgementsReturnsForeignStockRatioAndInceptionDate() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(
                baseBuilder(20L)
                        .stockType(StockType.FUND)
                        .isTarget(false)
                        .foreignStockRatio(new BigDecimal("45.00"))
                        .inceptionDate(LocalDate.of(2026, 7, 20))
                        .build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder().offset(0).size(10).build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getForeignStockRatio()).isEqualByComparingTo("45.00");
        assertThat(result.get(0).getInceptionDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    @DisplayName("해외주식·ETF·ETN처럼 비중요건이 없는 종목은 판단기준 값이 null로 반환된다")
    void selectJudgementsReturnsNullCriteriaForNonFundStockTypes() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(baseBuilder(20L).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder().offset(0).size(10).build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getForeignStockRatio()).isNull();
        assertThat(result.get(0).getInceptionDate()).isNull();
    }

    @Test
    @DisplayName("offset을 지정하면 그만큼 건너뛴 뒤부터 조회한다")
    void selectJudgementsAppliesOffsetForPagination() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(
                baseBuilder(20L).judgedAt(LocalDateTime.of(2026, 8, 7, 3, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(21L).judgedAt(LocalDateTime.of(2026, 8, 8, 3, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(22L).judgedAt(LocalDateTime.of(2026, 8, 9, 3, 0)).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder().offset(2).size(2).build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getJudgedAt()).isEqualTo(LocalDateTime.of(2026, 8, 7, 3, 0));
    }

    @Test
    @DisplayName("customerName 필터는 고객명에 부분일치하는 행만 조회한다")
    void selectJudgementsFiltersByCustomerNameContains() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        insertCustomer("ci-2", "김철수");
        targetProductMapper.insertJudgement(baseBuilder(23L).ciHash("ci-1").build());
        targetProductMapper.insertJudgement(baseBuilder(24L).ciHash("ci-2").build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder()
                                .customerName("길동")
                                .offset(0)
                                .size(10)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("stockType 필터는 해당 종목구분인 행만 조회한다")
    void selectJudgementsFiltersByStockType() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(
                baseBuilder(25L).stockType(StockType.FOREIGN_STOCK).build());
        targetProductMapper.insertJudgement(baseBuilder(26L).stockType(StockType.ETF).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder()
                                .stockType(StockType.ETF)
                                .offset(0)
                                .size(10)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockType()).isEqualTo(StockType.ETF);
    }

    @Test
    @DisplayName("isTarget 필터는 대상상품 여부가 일치하는 행만 조회한다")
    void selectJudgementsFiltersByIsTarget() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(baseBuilder(27L).isTarget(true).build());
        targetProductMapper.insertJudgement(baseBuilder(28L).isTarget(false).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder()
                                .isTarget(false)
                                .offset(0)
                                .size(10)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIsTarget()).isFalse();
    }

    @Test
    @DisplayName("tradeType 필터는 해당 거래유형인 행만 조회한다")
    void selectJudgementsFiltersByTradeType() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(baseBuilder(50L).tradeType(TradeType.BUY).build());
        targetProductMapper.insertJudgement(
                baseBuilder(51L).tradeType(TradeType.INHERITANCE).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder()
                                .tradeType(TradeType.INHERITANCE)
                                .offset(0)
                                .size(10)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTradeType()).isEqualTo(TradeType.INHERITANCE);
    }

    @Test
    @DisplayName("countFilteredJudgements는 tradeType 필터에 맞는 행만 센다")
    void countFilteredJudgementsFiltersByTradeType() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(baseBuilder(52L).tradeType(TradeType.GIFT).build());
        targetProductMapper.insertJudgement(baseBuilder(53L).tradeType(TradeType.GIFT).build());
        targetProductMapper.insertJudgement(baseBuilder(54L).tradeType(TradeType.BUY).build());

        int count =
                targetProductMapper.countFilteredJudgements(
                        TargetProductSearchDTO.builder().tradeType(TradeType.GIFT).build());

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("inheritanceGiftOnly 필터는 상속·증여 거래유형인 행만 조회한다")
    void selectJudgementsFiltersByInheritanceGiftOnly() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(
                baseBuilder(55L).tradeType(TradeType.INHERITANCE).build());
        targetProductMapper.insertJudgement(baseBuilder(56L).tradeType(TradeType.GIFT).build());
        targetProductMapper.insertJudgement(baseBuilder(57L).tradeType(TradeType.BUY).build());
        targetProductMapper.insertJudgement(baseBuilder(58L).tradeType(TradeType.SELL).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder()
                                .inheritanceGiftOnly(true)
                                .offset(0)
                                .size(10)
                                .build());

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(TargetProductJudgementListDTO::getTradeType)
                .containsExactlyInAnyOrder(TradeType.INHERITANCE, TradeType.GIFT);
    }

    @Test
    @DisplayName("judgedAtFrom/judgedAtTo 필터는 그 범위 안의 judged_at인 행만 조회한다")
    void selectJudgementsFiltersByJudgedAtRange() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(
                baseBuilder(59L).judgedAt(LocalDateTime.of(2026, 8, 6, 23, 59)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(60L).judgedAt(LocalDateTime.of(2026, 8, 7, 0, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(61L).judgedAt(LocalDateTime.of(2026, 8, 7, 23, 59)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(62L).judgedAt(LocalDateTime.of(2026, 8, 8, 0, 0)).build());

        List<TargetProductJudgementListDTO> result =
                targetProductMapper.selectJudgements(
                        TargetProductSearchDTO.builder()
                                .judgedAtFrom(LocalDateTime.of(2026, 8, 7, 0, 0))
                                .judgedAtTo(LocalDateTime.of(2026, 8, 8, 0, 0))
                                .offset(0)
                                .size(10)
                                .build());

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(TargetProductJudgementListDTO::getJudgedAt)
                .containsExactlyInAnyOrder(
                        LocalDateTime.of(2026, 8, 7, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59));
    }

    @Test
    @DisplayName("저장된 판정 결과의 전체 건수를 반환한다")
    void countJudgementsReturnsTotalRowCount() {
        targetProductMapper.insertJudgement(baseBuilder(30L).build());
        targetProductMapper.insertJudgement(baseBuilder(31L).build());
        targetProductMapper.insertJudgement(baseBuilder(32L).build());

        int count = targetProductMapper.countJudgements();

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("저장된 판정 결과가 없으면 전체 건수는 0이다")
    void countJudgementsReturnsZeroWhenNoRows() {
        int count = targetProductMapper.countJudgements();

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("countFilteredJudgements는 필터 조건에 맞는 행만 센다")
    void countFilteredJudgementsCountsOnlyMatchingRows() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(baseBuilder(33L).isTarget(true).build());
        targetProductMapper.insertJudgement(baseBuilder(34L).isTarget(true).build());
        targetProductMapper.insertJudgement(baseBuilder(35L).isTarget(false).build());

        int count =
                targetProductMapper.countFilteredJudgements(
                        TargetProductSearchDTO.builder().isTarget(true).build());

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("countFilteredJudgements는 필터 조건이 없으면 전체 건수를 반환한다")
    void countFilteredJudgementsReturnsTotalWhenNoFilterGiven() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        targetProductMapper.insertJudgement(baseBuilder(36L).build());
        targetProductMapper.insertJudgement(baseBuilder(37L).build());

        int count =
                targetProductMapper.countFilteredJudgements(
                        TargetProductSearchDTO.builder().build());

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘 판정 건수는 judged_at이 오늘 날짜인 행만 센다")
    void selectSummaryCountsOnlyTodaysJudgements() {
        LocalDate today = LocalDate.of(2026, 8, 7);
        LocalDate tomorrow = today.plusDays(1);
        targetProductMapper.insertJudgement(
                baseBuilder(40L).judgedAt(LocalDateTime.of(2026, 8, 7, 9, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(41L).judgedAt(LocalDateTime.of(2026, 8, 7, 15, 30)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(42L).judgedAt(LocalDateTime.of(2026, 8, 6, 23, 59)).build());

        TargetProductSummaryDTO summary = targetProductMapper.selectSummary(today, tomorrow);

        assertThat(summary.getTodayJudgementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("judged_at이 오늘 자정 정각이면 포함되고, 다음날 자정 정각이면 제외된다")
    void selectSummaryIncludesTodayMidnightAndExcludesTomorrowMidnight() {
        LocalDate today = LocalDate.of(2026, 8, 7);
        LocalDate tomorrow = today.plusDays(1);
        targetProductMapper.insertJudgement(
                baseBuilder(46L).judgedAt(LocalDateTime.of(2026, 8, 7, 0, 0)).build());
        targetProductMapper.insertJudgement(
                baseBuilder(47L).judgedAt(LocalDateTime.of(2026, 8, 8, 0, 0)).build());

        TargetProductSummaryDTO summary = targetProductMapper.selectSummary(today, tomorrow);

        assertThat(summary.getTodayJudgementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 대상상품 건수와 순매수 합계는 isTarget이 true인 행만 집계한다")
    void selectSummaryCountsAndSumsTargetJudgementsOnly() {
        LocalDate today = LocalDate.of(2026, 8, 7);
        LocalDate tomorrow = today.plusDays(1);
        targetProductMapper.insertJudgement(
                baseBuilder(43L)
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 9, 0))
                        .isTarget(true)
                        .netBuyAmount(new BigDecimal("1000000.00"))
                        .build());
        targetProductMapper.insertJudgement(
                baseBuilder(44L)
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 10, 0))
                        .isTarget(true)
                        .netBuyAmount(new BigDecimal("500000.00"))
                        .build());
        targetProductMapper.insertJudgement(
                baseBuilder(45L)
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 11, 0))
                        .isTarget(false)
                        .netBuyAmount(new BigDecimal("2000000.00"))
                        .build());

        TargetProductSummaryDTO summary = targetProductMapper.selectSummary(today, tomorrow);

        assertThat(summary.getTodayTargetCount()).isEqualTo(2);
        assertThat(summary.getTodayTargetNetBuyAmount()).isEqualByComparingTo("1500000.00");
    }

    @Test
    @DisplayName("오늘 판정이 없으면 건수와 합계 모두 0을 반환한다(NULL이 아니라)")
    void selectSummaryReturnsZeroWhenNoJudgementsToday() {
        LocalDate today = LocalDate.of(2026, 8, 7);
        LocalDate tomorrow = today.plusDays(1);

        TargetProductSummaryDTO summary = targetProductMapper.selectSummary(today, tomorrow);

        assertThat(summary.getTodayJudgementCount()).isEqualTo(0);
        assertThat(summary.getTodayTargetCount()).isEqualTo(0);
        assertThat(summary.getTodayTargetNetBuyAmount()).isEqualByComparingTo("0");
        assertThat(summary.getTodayInheritanceGiftCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("오늘 상속·증여 산입 건수는 INHERITANCE·GIFT만 세고, 매수·매도와 어제 건은 제외한다")
    void selectSummaryCountsOnlyTodaysInheritanceAndGiftTradeTypes() {
        LocalDate today = LocalDate.of(2026, 8, 7);
        LocalDate tomorrow = today.plusDays(1);
        targetProductMapper.insertJudgement(
                baseBuilder(60L)
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 9, 0))
                        .tradeType(TradeType.INHERITANCE)
                        .build());
        targetProductMapper.insertJudgement(
                baseBuilder(61L)
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 10, 0))
                        .tradeType(TradeType.GIFT)
                        .build());
        targetProductMapper.insertJudgement(
                baseBuilder(62L)
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 11, 0))
                        .tradeType(TradeType.BUY)
                        .build());
        targetProductMapper.insertJudgement(
                baseBuilder(63L)
                        .judgedAt(LocalDateTime.of(2026, 8, 6, 23, 59))
                        .tradeType(TradeType.INHERITANCE)
                        .build());

        TargetProductSummaryDTO summary = targetProductMapper.selectSummary(today, tomorrow);

        assertThat(summary.getTodayInheritanceGiftCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("cutoff 이전에 첫 실패한 건만 고객명과 함께 firstFailedAt 오름차순으로 조회한다")
    void selectPermanentFailuresReturnsRowsAtOrBeforeCutoffOrderedByFirstFailedAtAsc()
            throws SQLException {
        insertCustomer("ci-1", "홍길동");
        insertFailure(1L, "ci-1", LocalDate.of(2026, 3, 1), LocalDateTime.of(2026, 8, 1, 0, 0));
        insertFailure(2L, "ci-1", LocalDate.of(2026, 3, 2), LocalDateTime.of(2026, 8, 3, 0, 0));
        insertFailure(3L, "ci-1", LocalDate.of(2026, 3, 3), LocalDateTime.of(2026, 8, 6, 0, 0));

        List<TargetProductJudgementFailureListDTO> result =
                targetProductMapper.selectPermanentFailures(
                        LocalDateTime.of(2026, 8, 3, 0, 0), 0, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFirstFailedAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        assertThat(result.get(1).getFirstFailedAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0));
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("offset을 지정하면 그만큼 건너뛴 뒤부터 조회한다")
    void selectPermanentFailuresAppliesOffsetForPagination() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        insertFailure(1L, "ci-1", LocalDate.of(2026, 3, 1), LocalDateTime.of(2026, 8, 1, 0, 0));
        insertFailure(2L, "ci-1", LocalDate.of(2026, 3, 2), LocalDateTime.of(2026, 8, 2, 0, 0));

        List<TargetProductJudgementFailureListDTO> result =
                targetProductMapper.selectPermanentFailures(
                        LocalDateTime.of(2026, 8, 9, 0, 0), 1, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFirstFailedAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 0, 0));
    }

    @Test
    @DisplayName("countPermanentFailures는 cutoff 이전에 첫 실패한 건만 센다")
    void countPermanentFailuresCountsOnlyRowsAtOrBeforeCutoff() throws SQLException {
        insertCustomer("ci-1", "홍길동");
        insertFailure(1L, "ci-1", LocalDate.of(2026, 3, 1), LocalDateTime.of(2026, 8, 1, 0, 0));
        insertFailure(2L, "ci-1", LocalDate.of(2026, 3, 2), LocalDateTime.of(2026, 8, 5, 0, 0));

        int count = targetProductMapper.countPermanentFailures(LocalDateTime.of(2026, 8, 3, 0, 0));

        assertThat(count).isEqualTo(1);
    }

    private void insertFailure(
            Long mydataTradeId, String ciHash, LocalDate tradeDate, LocalDateTime firstFailedAt)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO target_product_judgement_failure "
                            + "(mydata_trade_id, ci_hash, trade_date, failure_count, last_error, first_failed_at, last_failed_at) "
                            + "VALUES ("
                            + mydataTradeId
                            + ", '"
                            + ciHash
                            + "', '"
                            + tradeDate
                            + "', 1, 'error', '"
                            + firstFailedAt
                            + "', '"
                            + firstFailedAt
                            + "')");
        }
    }

    private void insertCustomer(String ciHash, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO customer (name, birth_date, investor_type, ci_hash) VALUES ('"
                            + name
                            + "', '1990-01-01', 'NEUTRAL', '"
                            + ciHash
                            + "')");
        }
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE customer (
                        customer_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        name VARCHAR(50) NOT NULL,
                        birth_date DATE NOT NULL,
                        phone VARCHAR(20),
                        investor_type VARCHAR(20) NOT NULL,
                        ci_hash VARCHAR(64) NOT NULL,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE target_product_judgement (
                        judgement_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        mydata_trade_id BIGINT NOT NULL,
                        ci_hash VARCHAR(64) NOT NULL,
                        stock_type VARCHAR(15) NOT NULL,
                        fund_code VARCHAR(12),
                        fund_name VARCHAR(100),
                        ticker VARCHAR(20),
                        is_target BOOLEAN NOT NULL,
                        foreign_stock_ratio DECIMAL(5, 2),
                        inception_date DATE,
                        judged_at DATETIME NOT NULL,
                        trade_type VARCHAR(15) NOT NULL,
                        amount DECIMAL(15, 2) NOT NULL,
                        trade_date DATE NOT NULL,
                        net_buy_amount DECIMAL(15, 2) NOT NULL,
                        CONSTRAINT uq_target_product__trade UNIQUE (mydata_trade_id)
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE target_product_judgement_failure (
                        failure_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        mydata_trade_id BIGINT NOT NULL,
                        ci_hash VARCHAR(64) NOT NULL,
                        trade_date DATE NOT NULL,
                        failure_count INT NOT NULL,
                        last_error VARCHAR(500),
                        first_failed_at DATETIME NOT NULL,
                        last_failed_at DATETIME NOT NULL,
                        CONSTRAINT uq_target_product_failure__trade UNIQUE (mydata_trade_id)
                    )
                    """);
        }
    }
}
