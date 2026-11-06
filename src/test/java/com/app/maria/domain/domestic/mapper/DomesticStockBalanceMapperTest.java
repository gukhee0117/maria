package com.app.maria.domain.domestic.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.domestic.dto.DomesticAccountLiteDTO;
import com.app.maria.domain.domestic.dto.DomesticCashHeavyAccountDTO;
import com.app.maria.domain.domestic.dto.DomesticFundHoldingDetailDTO;
import com.app.maria.domain.domestic.dto.DomesticHoldingDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentListDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentSearchDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentSummaryDTO;
import com.app.maria.domain.domestic.dto.DomesticRestrictedHoldingDTO;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

class DomesticStockBalanceMapperTest {

    private static final LocalDateTime PURCHASE_DATE = LocalDateTime.of(2026, 3, 5, 9, 0);

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private DomesticStockBalanceMapper domesticStockBalanceMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader =
                Resources.getResourceAsReader("mybatis-domesticstockbalance-test-config.xml")) {
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
        domesticStockBalanceMapper = sqlSession.getMapper(DomesticStockBalanceMapper.class);
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

    // ---- selectAccountSummaries ----

    @Test
    @DisplayName("보유종목이 없는 계좌도 예탁금 정보를 담아 목록에 나온다 (LEFT JOIN)")
    void selectAccountSummariesIncludesAccountsWithNoHoldings() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "1000000", "OPENED");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(condition(null, null, 0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
        assertThat(result.get(0).getCashAmount()).isEqualByComparingTo("1000000");
        assertThat(result.get(0).getHoldingCount()).isEqualTo(0);
        assertThat(result.get(0).isHasRestrictedHolding()).isFalse();
    }

    @Test
    @DisplayName("holdingCount는 전량매도·보유종료 상태를 제외하고 센다")
    void selectAccountSummariesCountsOnlyActiveHoldings() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertDomesticProduct(2L, "000660", "SK하이닉스", "STOCK", null, null);
        insertDomesticProduct(3L, "035420", "NAVER", "STOCK", null, null);
        insertBalance(1L, 1L, "HOLDING", "10");
        insertBalance(1L, 2L, "SOLD_OUT", "0");
        insertBalance(1L, 3L, "TERMINATED", "0");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(condition(null, null, 0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHoldingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("거래제한/거래정지 보유종목이 하나라도 있으면 hasRestrictedHolding이 true다")
    void selectAccountSummariesFlagsRestrictedHolding() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertDomesticProduct(2L, "000660", "SK하이닉스", "STOCK", null, null);
        insertBalance(1L, 1L, "HOLDING", "10");
        insertBalance(1L, 2L, "TRADE_SUSPENDED", "5");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(condition(null, null, 0, 20));

        assertThat(result.get(0).isHasRestrictedHolding()).isTrue();
    }

    @Test
    @DisplayName("keyword 필터는 고객명에 부분일치하는 계좌만 조회한다")
    void selectAccountSummariesFiltersByKeywordMatchingCustomerName() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(condition("홍길동", null, 0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("keyword 필터는 계좌번호에 부분일치하는 계좌도 조회한다")
    void selectAccountSummariesFiltersByKeywordMatchingAccountNo() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(
                        condition("2222222222", null, 0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("status 필터는 그 상태의 보유종목을 가진 계좌만 조회한다")
    void selectAccountSummariesFiltersByStatus() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalance(1L, 1L, "TRADE_RESTRICTED", "10");
        insertBalance(2L, 1L, "HOLDING", "10");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(
                        condition(null, DomesticStockStatus.TRADE_RESTRICTED, 0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("OPENED 상태가 아닌 계좌는 목록에서 제외한다")
    void selectAccountSummariesExcludesNonOpenedAccounts() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "CLOSED");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(condition(null, null, 0, 20));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("hasRestrictedHolding=true면 거래제한·정지 보유종목이 있는 계좌만 조회한다")
    void selectAccountSummariesFiltersByHasRestrictedHolding() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalance(1L, 1L, "TRADE_SUSPENDED", "10");
        insertBalance(2L, 1L, "HOLDING", "10");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(
                        DomesticInvestmentSearchDTO.builder()
                                .hasRestrictedHolding(true)
                                .offset(0)
                                .size(20)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("unpurchasableAccountIds가 주어지면 해당 계좌ID만 조회한다")
    void selectAccountSummariesFiltersByUnpurchasableAccountIds() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(
                        DomesticInvestmentSearchDTO.builder()
                                .unpurchasableAccountIds(List.of(2L))
                                .offset(0)
                                .size(20)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("hasRecentBuy=true면 기준일 이후 매수한 보유종목이 있는 계좌만 조회한다")
    void selectAccountSummariesFiltersByHasRecentBuyTrue() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalanceWithDate(1L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 8, 15, 9, 0));
        insertBalanceWithDate(2L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 7, 1, 9, 0));

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(
                        DomesticInvestmentSearchDTO.builder()
                                .hasRecentBuy(true)
                                .recentBuySinceDate(LocalDate.of(2026, 8, 1))
                                .offset(0)
                                .size(20)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("hasRecentBuy=false면 기준일 이후 매수 이력이 없는 계좌만 조회한다")
    void selectAccountSummariesFiltersByHasRecentBuyFalse() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalanceWithDate(1L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 8, 15, 9, 0));
        insertBalanceWithDate(2L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 7, 1, 9, 0));

        List<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaries(
                        DomesticInvestmentSearchDTO.builder()
                                .hasRecentBuy(false)
                                .recentBuySinceDate(LocalDate.of(2026, 8, 1))
                                .offset(0)
                                .size(20)
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("offset·size로 페이지네이션이 적용된다")
    void selectAccountSummariesAppliesOffsetAndSizeForPagination() throws SQLException {
        insertCustomer(1L, "고객1");
        insertCustomer(2L, "고객2");
        insertCustomer(3L, "고객3");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertAccount(3L, 3L, "3333333333", "0", "OPENED");

        List<DomesticInvestmentListDTO> firstPage =
                domesticStockBalanceMapper.selectAccountSummaries(condition(null, null, 0, 2));
        List<DomesticInvestmentListDTO> secondPage =
                domesticStockBalanceMapper.selectAccountSummaries(condition(null, null, 2, 2));

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
    }

    // ---- countAccountSummaries ----

    @Test
    @DisplayName("countAccountSummaries는 필터가 적용된 계좌 수를 반환한다")
    void countAccountSummariesMatchesFilteredRowCount() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");

        int count = domesticStockBalanceMapper.countAccountSummaries(condition("홍길동", null, 0, 20));

        assertThat(count).isEqualTo(1);
    }

    // ---- selectAccountSummaryById ----

    @Test
    @DisplayName("accountId로 조회하면 해당 계좌의 요약 정보를 반환한다")
    void selectAccountSummaryByIdReturnsSummary() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "500000", "OPENED");

        Optional<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaryById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getAccountNo()).isEqualTo("1234567890");
        assertThat(result.get().getCashAmount()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("존재하지 않는 accountId는 빈 Optional을 반환한다")
    void selectAccountSummaryByIdReturnsEmptyWhenNotFound() {
        Optional<DomesticInvestmentListDTO> result =
                domesticStockBalanceMapper.selectAccountSummaryById(999L);

        assertThat(result).isEmpty();
    }

    // ---- selectHoldingsByAccountId ----

    @Test
    @DisplayName("보유종목을 종목 정보와 함께 최근매수일 최신순으로 반환한다")
    void selectHoldingsByAccountIdReturnsHoldingsOrderedByLastPurchaseDateDesc()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");
        insertDomesticProduct(1L, "448630", "TIGER 미국배당다우존스", "FUND", "85.50", "2023-05-10");
        insertDomesticProduct(2L, "005930", "삼성전자", "STOCK", null, null);
        insertBalanceWithDate(1L, 1L, "HOLDING", "10", PURCHASE_DATE);
        insertBalanceWithDate(1L, 2L, "HOLDING", "20", PURCHASE_DATE.plusDays(1));

        try (Statement statement = sqlSession.getConnection().createStatement()) {
            statement.executeUpdate(
                    "UPDATE domestic_product SET sector_name = '반도체' WHERE domestic_product_id = 2");
        }

        List<DomesticHoldingDTO> result = domesticStockBalanceMapper.selectHoldingsByAccountId(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTicker()).isEqualTo("005930");
        assertThat(result.get(0).getSectorName()).isEqualTo("반도체");
        assertThat(result.get(1).getTicker()).isEqualTo("448630");
        assertThat(result.get(1).getDomesticStockRatio()).isEqualByComparingTo("85.50");
    }

    @Test
    @DisplayName("보유종목이 없으면 빈 목록을 반환한다")
    void selectHoldingsByAccountIdReturnsEmptyListWhenNoHoldings() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");

        List<DomesticHoldingDTO> result = domesticStockBalanceMapper.selectHoldingsByAccountId(1L);

        assertThat(result).isEmpty();
    }

    // ---- selectSummaryStats ----

    @Test
    @DisplayName("totalAccountCount은 OPENED 상태 계좌만 센다")
    void selectSummaryStatsCountsOnlyOpenedAccounts() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "CLOSED");

        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getTotalAccountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("restrictedAccountCount은 거래제한·거래정지 보유종목이 있는 계좌 수를 중복 없이 센다")
    void selectSummaryStatsCountsDistinctRestrictedAccounts() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertDomesticProduct(2L, "000660", "SK하이닉스", "STOCK", null, null);
        insertBalance(1L, 1L, "TRADE_RESTRICTED", "10");
        insertBalance(1L, 2L, "TRADE_SUSPENDED", "5");

        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getRestrictedAccountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("totalCashAmount은 OPENED 계좌의 예탁금만 합산한다")
    void selectSummaryStatsSumsCashAmountForOpenedAccountsOnly() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "1000000", "OPENED");
        insertAccount(2L, 2L, "2222222222", "500000", "CLOSED");

        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getTotalCashAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("domesticStockAmount·domesticFundAmount은 종목구분별로 나눠서 합산하고, 전량매도 보유는 제외한다")
    void selectSummaryStatsSumsStockAndFundAmountsSeparately() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertDomesticProduct(2L, "448630", "TIGER 미국배당다우존스", "FUND", "85.00", "2020-01-01");
        insertDomesticProduct(3L, "000660", "SK하이닉스", "STOCK", null, null);
        insertBalance(1L, 1L, "HOLDING", "10000");
        insertBalance(1L, 2L, "HOLDING", "5000");
        insertBalance(1L, 3L, "SOLD_OUT", "9999");

        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getDomesticStockAmount()).isEqualByComparingTo("1000000");
        assertThat(result.getDomesticFundAmount()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("stockHoldingAccountCount·fundHoldingAccountCount은 종목구분별 보유 계좌 수를 중복 없이 센다")
    void selectSummaryStatsCountsHoldingAccountsPerType() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertDomesticProduct(2L, "000660", "SK하이닉스", "STOCK", null, null);
        insertDomesticProduct(3L, "448630", "TIGER 미국배당다우존스", "FUND", "85.00", "2020-01-01");
        insertBalance(1L, 1L, "HOLDING", "10");
        insertBalance(1L, 2L, "HOLDING", "5");
        insertBalance(2L, 3L, "HOLDING", "20");

        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getStockHoldingAccountCount()).isEqualTo(1);
        assertThat(result.getFundHoldingAccountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("recentBuyAccountCount은 last_purchase_date가 기준일 이후인 계좌 수를 중복 없이 센다")
    void selectSummaryStatsCountsRecentBuyAccounts() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalanceWithDate(1L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 8, 10, 9, 0));
        insertBalanceWithDate(2L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 7, 1, 9, 0));

        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getRecentBuyAccountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("데이터가 없으면 모든 값이 0을 반환한다(NULL이 아니라)")
    void selectSummaryStatsReturnsZerosWhenNoData() {
        DomesticInvestmentSummaryDTO result =
                domesticStockBalanceMapper.selectSummaryStats(LocalDate.of(2026, 8, 1));

        assertThat(result.getTotalAccountCount()).isEqualTo(0);
        assertThat(result.getRestrictedAccountCount()).isEqualTo(0);
        assertThat(result.getTotalCashAmount()).isEqualByComparingTo("0");
        assertThat(result.getDomesticStockAmount()).isEqualByComparingTo("0");
        assertThat(result.getDomesticFundAmount()).isEqualByComparingTo("0");
        assertThat(result.getStockHoldingAccountCount()).isEqualTo(0);
        assertThat(result.getFundHoldingAccountCount()).isEqualTo(0);
        assertThat(result.getRecentBuyAccountCount()).isEqualTo(0);
    }

    // ---- selectActiveFundHoldings ----

    @Test
    @DisplayName("OPENED 계좌의 활성(전량매도·보유종료 아닌) 펀드 보유 건마다 고객·계좌·종목정보를 반환한다")
    void selectActiveFundHoldingsReturnsOneRowPerActiveFundHolding() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertDomesticProduct(1L, "448630", "TIGER 미국배당다우존스", "FUND", "85.00", "2020-01-01");
        insertBalance(1L, 1L, "HOLDING", "10");

        List<DomesticFundHoldingDetailDTO> result =
                domesticStockBalanceMapper.selectActiveFundHoldings();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
        assertThat(result.get(0).getAccountNo()).isEqualTo("1111111111");
        assertThat(result.get(0).getProductName()).isEqualTo("TIGER 미국배당다우존스");
        assertThat(result.get(0).getDomesticStockRatio()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("STOCK 종목과 전량매도·보유종료 상태인 펀드는 제외한다")
    void selectActiveFundHoldingsExcludesStockAndInactiveHoldings() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertDomesticProduct(2L, "448630", "TIGER 미국배당다우존스", "FUND", "85.00", "2020-01-01");
        insertBalance(1L, 1L, "HOLDING", "10");
        insertBalance(1L, 2L, "SOLD_OUT", "10");

        List<DomesticFundHoldingDetailDTO> result =
                domesticStockBalanceMapper.selectActiveFundHoldings();

        assertThat(result).isEmpty();
    }

    // ---- selectAccountsByRecentBuyStatus ----

    @Test
    @DisplayName("hasRecentBuy=true면 기준일 이후 활성 매수가 있는 계좌만 반환한다")
    void selectAccountsByRecentBuyStatusReturnsAccountsWithRecentPurchaseWhenTrue()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalanceWithDate(1L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 8, 15, 9, 0));
        insertBalanceWithDate(2L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 7, 1, 9, 0));

        List<DomesticAccountLiteDTO> result =
                domesticStockBalanceMapper.selectAccountsByRecentBuyStatus(
                        LocalDate.of(2026, 8, 1), true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("hasRecentBuy=false면 기준일 이후 활성 매수가 없는 계좌만 반환한다(보유종목 없는 계좌 포함)")
    void selectAccountsByRecentBuyStatusReturnsAccountsWithoutRecentPurchaseWhenFalse()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertCustomer(3L, "이영희");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "OPENED");
        insertAccount(3L, 3L, "3333333333", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalanceWithDate(1L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 8, 15, 9, 0));
        insertBalanceWithDate(2L, 1L, "HOLDING", "10", LocalDateTime.of(2026, 7, 1, 9, 0));
        // 계좌 3은 보유종목 자체가 없음

        List<DomesticAccountLiteDTO> result =
                domesticStockBalanceMapper.selectAccountsByRecentBuyStatus(
                        LocalDate.of(2026, 8, 1), false);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(DomesticAccountLiteDTO::getCustomerName)
                .containsExactlyInAnyOrder("김철수", "이영희");
    }

    // ---- selectRestrictedHoldings ----

    @Test
    @DisplayName("거래제한·거래정지 보유종목을 고객명·계좌번호·종목정보와 함께 반환한다")
    void selectRestrictedHoldingsReturnsDetailsForRestrictedAndSuspendedHoldings()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");
        insertDomesticProduct(1L, "207940", "삼성바이오로직스", "STOCK", null, null);
        insertBalance(1L, 1L, "TRADE_SUSPENDED", "10");

        List<DomesticRestrictedHoldingDTO> result =
                domesticStockBalanceMapper.selectRestrictedHoldings();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
        assertThat(result.get(0).getAccountNo()).isEqualTo("1234567890");
        assertThat(result.get(0).getProductName()).isEqualTo("삼성바이오로직스");
        assertThat(result.get(0).getStatus()).isEqualTo(DomesticStockStatus.TRADE_SUSPENDED);
    }

    @Test
    @DisplayName("거래제한·거래정지가 아닌 보유종목은 제외한다")
    void selectRestrictedHoldingsExcludesOtherStatuses() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalance(1L, 1L, "HOLDING", "10");

        List<DomesticRestrictedHoldingDTO> result =
                domesticStockBalanceMapper.selectRestrictedHoldings();

        assertThat(result).isEmpty();
    }

    // ---- selectCashHeavyAccounts ----

    @Test
    @DisplayName("예탁금 비중이 높은 계좌를 반환한다")
    void selectCashHeavyAccountsReturnsAccountsAtOrAboveHalfCashRatio() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "1000000", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalance(1L, 1L, "HOLDING", "10000");

        List<DomesticCashHeavyAccountDTO> result =
                domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCashAmount()).isEqualByComparingTo("1000000");
        assertThat(result.get(0).getInvestedAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("예탁금 비중이 낮은 계좌도 제외되지 않고 비중 내림차순으로 포함된다")
    void selectCashHeavyAccountsIncludesLowRatioAccountsOrderedDesc() throws SQLException {
        insertCustomer(1L, "고비중");
        insertCustomer(2L, "저비중");
        insertAccount(1L, 1L, "1111111111", "1000000", "OPENED");
        insertAccount(2L, 2L, "2222222222", "100000", "OPENED");
        insertDomesticProduct(1L, "005930", "삼성전자", "STOCK", null, null);
        insertBalance(2L, 1L, "HOLDING", "9000");

        List<DomesticCashHeavyAccountDTO> result =
                domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCustomerName()).isEqualTo("고비중");
        assertThat(result.get(1).getCustomerName()).isEqualTo("저비중");
    }

    @Test
    @DisplayName("예탁금이 0원인 계좌도 제외되지 않고 포함된다")
    void selectCashHeavyAccountsIncludesZeroCashAccounts() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "0", "OPENED");

        List<DomesticCashHeavyAccountDTO> result =
                domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCashAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("보유종목이 전혀 없어 투자금액이 0이어도 예탁금이 있으면 비중 100%로 잡혀 포함된다")
    void selectCashHeavyAccountsIncludesAccountsWithNoHoldingsAsFullCash() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890", "500000", "OPENED");

        List<DomesticCashHeavyAccountDTO> result =
                domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getInvestedAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("offset·size로 페이지네이션이 적용된다")
    void selectCashHeavyAccountsAppliesOffsetAndSizeForPagination() throws SQLException {
        insertCustomer(1L, "고객1");
        insertCustomer(2L, "고객2");
        insertCustomer(3L, "고객3");
        insertAccount(1L, 1L, "1111111111", "300000", "OPENED");
        insertAccount(2L, 2L, "2222222222", "200000", "OPENED");
        insertAccount(3L, 3L, "3333333333", "100000", "OPENED");

        List<DomesticCashHeavyAccountDTO> firstPage =
                domesticStockBalanceMapper.selectCashHeavyAccounts(0, 2);
        List<DomesticCashHeavyAccountDTO> secondPage =
                domesticStockBalanceMapper.selectCashHeavyAccounts(2, 2);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
    }

    // ---- countCashHeavyAccounts ----

    @Test
    @DisplayName("countCashHeavyAccounts는 OPENED 상태 계좌 수를 반환한다")
    void countCashHeavyAccountsCountsOnlyOpenedAccounts() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111", "0", "OPENED");
        insertAccount(2L, 2L, "2222222222", "0", "CLOSED");

        int count = domesticStockBalanceMapper.countCashHeavyAccounts();

        assertThat(count).isEqualTo(1);
    }

    private DomesticInvestmentSearchDTO condition(
            String keyword, DomesticStockStatus status, int offset, int size) {
        return DomesticInvestmentSearchDTO.builder()
                .keyword(keyword)
                .status(status)
                .offset(offset)
                .size(size)
                .build();
    }

    private void insertCustomer(Long customerId, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO customer (customer_id, name) VALUES (%d, '%s')"
                            .formatted(customerId, name));
        }
    }

    private void insertAccount(
            Long accountId, Long customerId, String accountNo, String amount, String status)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO account (account_id, customer_id, account_no, amount, status)
                    VALUES (%d, %d, '%s', %s, '%s')
                    """
                            .formatted(accountId, customerId, accountNo, amount, status));
        }
    }

    private void insertDomesticProduct(
            Long id, String ticker, String name, String type, String ratio, String inceptionDate)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            String ratioValue = ratio == null ? "NULL" : "'" + ratio + "'";
            String inceptionValue = inceptionDate == null ? "NULL" : "'" + inceptionDate + "'";
            statement.execute(
                    """
                    INSERT INTO domestic_product
                    (domestic_product_id, ticker, name, market, type, domestic_stock_ratio, inception_date)
                    VALUES (%d, '%s', '%s', 'KOSPI', '%s', %s, %s)
                    """
                            .formatted(id, ticker, name, type, ratioValue, inceptionValue));
        }
    }

    private void insertBalance(Long accountId, Long productId, String status, String qty)
            throws SQLException {
        insertBalanceWithDate(accountId, productId, status, qty, PURCHASE_DATE);
    }

    private void insertBalanceWithDate(
            Long accountId, Long productId, String status, String qty, LocalDateTime purchaseDate)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO domestic_stock_balance
                    (account_id, domestic_product_id, qty, status, last_purchase_date, avg_purchase_price)
                    VALUES (%d, %d, %s, '%s', '%s', 100)
                    """
                            .formatted(accountId, productId, qty, status, purchaseDate));
        }
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE customer (
                        customer_id BIGINT PRIMARY KEY,
                        name VARCHAR(50) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE account (
                        account_id BIGINT PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        account_no VARCHAR(10),
                        amount DECIMAL(15, 4) NOT NULL DEFAULT 0,
                        status VARCHAR(20) NOT NULL DEFAULT 'OPENED'
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE domestic_product (
                        domestic_product_id BIGINT PRIMARY KEY,
                        ticker VARCHAR(20) NOT NULL,
                        name VARCHAR(100) NOT NULL,
                        market VARCHAR(50),
                        type VARCHAR(15) NOT NULL,
                        domestic_stock_ratio DECIMAL(5,2),
                        inception_date DATE,
                        sector_name VARCHAR(100)
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE domestic_stock_balance (
                        domestic_stock_balance_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        domestic_product_id BIGINT NOT NULL,
                        qty DECIMAL(15, 4) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        last_purchase_date DATETIME NOT NULL,
                        avg_purchase_price DECIMAL(15, 4) NOT NULL
                    )
                    """);
        }
    }
}
