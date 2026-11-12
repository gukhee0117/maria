package com.app.maria.domain.fundflow.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalCauseDTO;
import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalSummaryRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Comparator;
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

/** 1년 미경과 원금 인출 비율 쿼리를 H2(MySQL 모드)에서 운영과 같은 SQL 로 검증한다. */
class FundFlowEarlyWithdrawalMapperTest {

    private static final LocalDate CLOCK_DATE = LocalDate.of(2026, 11, 18);
    private static final String IMMATURE = "IMMATURE_PRINCIPAL_INCLUDED";

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private SqlSession sqlSession;
    private FundFlowMapper fundFlowMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-fundflow-test-config.xml")) {
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
        fundFlowMapper = sqlSession.getMapper(FundFlowMapper.class);
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

    /**
     * 계좌 1 = 일반, 2 = 취소·실패 인출만, 3 = 두 원인에 걸침, 4 = 해지, 5 = 인출 없음. 분모 대상 환전 1~5·8(합 55,000,000, 계좌
     * 5개)과 대상 밖 환전(가정산 6, 확정일이 clockDate 인 7)을 섞는다. 환전 번호 = left_amount 번호.
     */
    private void seedMixed() throws SQLException {
        insertLot(1, 1L, "FINALIZED", "10000000", "2026-03-24 10:00:00");
        insertLot(2, 1L, "FINALIZED", "20000000", "2026-04-10 10:00:00");
        insertLot(3, 2L, "FINALIZED", "8000000", "2026-04-11 10:00:00");
        insertLot(4, 3L, "FINALIZED", "5000000", "2026-04-12 10:00:00");
        insertLot(5, 4L, "FINALIZED", "9000000", "2026-05-21 10:00:00"); // 해지 계좌도 분모에 포함
        insertLot(6, 1L, "PROVISIONAL", null, null); // 가정산
        insertLot(7, 2L, "FINALIZED", "1000000", "2026-11-18 00:00:00"); // 확정일이 clockDate
        insertLot(8, 5L, "FINALIZED", "3000000", "2026-05-03 10:00:00");

        // 해지 강제 인출(계좌 4): 1년 미경과 원금 9,000,000 + 수익금 500,000
        insertWithdrawal(1, 4L, "COMPLETED");
        insertAllocation(1, 5L, "9000000", "2026-09-01 10:00:00", IMMATURE);
        insertAllocation(1, null, "500000", "2026-09-01 10:00:00", "EARNINGS_ONLY");
        insertClosure(4L, "COMPLETED", 1L);
        // 그 밖의 인출(계좌 1)
        insertWithdrawal(2, 1L, "COMPLETED");
        insertAllocation(2, 1L, "2000000", "2026-09-02 10:00:00", IMMATURE);
        insertClosure(1L, "REJECTED", 2L); // 완료되지 않은 해지 요청은 해지 강제로 보지 않는다
        // 계좌 3: 그 밖의 인출 + 해지 강제 인출
        insertWithdrawal(3, 3L, "COMPLETED");
        insertAllocation(3, 4L, "1000000", "2026-09-03 10:00:00", IMMATURE);
        insertWithdrawal(4, 3L, "COMPLETED");
        insertAllocation(4, 4L, "500000", "2026-09-04 10:00:00", IMMATURE);
        insertClosure(3L, "COMPLETED", 4L);
        // 제외: 취소·실패 인출, clockDate 이후 인출, 분모 밖 환전에서 나온 인출
        insertWithdrawal(5, 2L, "CANCELLED");
        insertAllocation(5, 3L, "3000000", "2026-09-05 10:00:00", IMMATURE);
        insertWithdrawal(6, 2L, "FAILED");
        insertAllocation(6, 3L, "1000000", "2026-09-06 10:00:00", IMMATURE);
        insertWithdrawal(7, 1L, "COMPLETED");
        insertAllocation(7, 2L, "4000000", "2026-11-18 10:00:00", IMMATURE);
        insertWithdrawal(8, 2L, "COMPLETED");
        insertAllocation(8, 7L, "700000", "2026-11-10 10:00:00", IMMATURE);
    }

    @Test
    @DisplayName("분모는 FINALIZED·확정일 < clockDate 환전의 계좌 수와 확정 원금이고 해지 계좌를 포함한다")
    void summaryDenominatorIncludesClosedAccounts() throws SQLException {
        seedMixed();

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);

        assertThat(summary.getFinalizedAccounts()).isEqualTo(5L);
        assertThat(summary.getFinalizedAmount()).isEqualByComparingTo("55000000");
    }

    @Test
    @DisplayName("분자는 완료 인출의 1년 미경과 원금 배분만 센다(취소·실패·clockDate 이후·분모 밖 환전·수익금 제외)")
    void summaryNumeratorCountsOnlyCompletedImmatureAllocationsOfDenominatorLots()
            throws SQLException {
        seedMixed();

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);

        // 9,000,000 + 2,000,000 + 1,000,000 + 500,000, 계좌 4·1·3
        assertThat(summary.getEarlyWithdrawnAmount()).isEqualByComparingTo("12500000");
        assertThat(summary.getEarlyAccounts()).isEqualTo(3L);
    }

    @Test
    @DisplayName("취소·실패 인출에 배분 행이 있어도 분자에서 뺀다")
    void cancelledAndFailedWithdrawalsAreExcluded() throws SQLException {
        insertLot(1, 1L, "FINALIZED", "10000000", "2026-03-24 10:00:00");
        insertWithdrawal(1, 1L, "CANCELLED");
        insertAllocation(1, 1L, "3000000", "2026-09-01 10:00:00", IMMATURE);
        insertWithdrawal(2, 1L, "FAILED");
        insertAllocation(2, 1L, "1000000", "2026-09-02 10:00:00", IMMATURE);
        insertWithdrawal(3, 1L, "REQUESTED");
        insertAllocation(3, 1L, "500000", "2026-09-03 10:00:00", IMMATURE);

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);

        assertThat(summary.getEarlyAccounts()).isZero();
        assertThat(summary.getEarlyWithdrawnAmount()).isEqualByComparingTo("0");
        assertThat(fundFlowMapper.selectEarlyWithdrawalByCause(CLOCK_DATE)).isEmpty();
    }

    @Test
    @DisplayName("원인: 완료된 해지 요청에 연결된 인출은 CLOSURE_FORCED, 나머지는 OTHER")
    void byCauseSplitsClosureForcedAndOther() throws SQLException {
        seedMixed();

        List<FundFlowEarlyWithdrawalCauseDTO> byCause =
                fundFlowMapper.selectEarlyWithdrawalByCause(CLOCK_DATE).stream()
                        .sorted(Comparator.comparing(FundFlowEarlyWithdrawalCauseDTO::getCause))
                        .toList();

        assertThat(byCause)
                .extracting(
                        FundFlowEarlyWithdrawalCauseDTO::getCause,
                        FundFlowEarlyWithdrawalCauseDTO::getAccounts)
                .containsExactly(tuple("CLOSURE_FORCED", 2L), tuple("OTHER", 2L));
        assertThat(byCause.get(0).getAmount())
                .isEqualByComparingTo("9500000"); // 9,000,000 + 500,000
        assertThat(byCause.get(1).getAmount())
                .isEqualByComparingTo("3000000"); // 2,000,000 + 1,000,000
    }

    @Test
    @DisplayName("대상이 없으면 분모·분자가 0이고 원인 행이 없다")
    void emptyWhenNothing() {
        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);

        assertThat(summary.getFinalizedAccounts()).isZero();
        assertThat(summary.getFinalizedAmount()).isEqualByComparingTo("0");
        assertThat(summary.getEarlyAccounts()).isZero();
        assertThat(summary.getEarlyWithdrawnAmount()).isEqualByComparingTo("0");
        assertThat(fundFlowMapper.selectEarlyWithdrawalByCause(CLOCK_DATE)).isEmpty();
    }

    @Test
    @DisplayName("불변식 ①: finalizedAmount = /overview 의 inflowTotal")
    void invariantFinalizedAmountEqualsOverviewInflowTotal() throws SQLException {
        seedMixed();

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);
        FundFlowOverviewDTO overview =
                fundFlowMapper.selectOverview(
                        FundFlowFilterDTO.builder()
                                .asOfDate(CLOCK_DATE.minusDays(1))
                                .clockDate(CLOCK_DATE)
                                .build());

        assertThat(summary.getFinalizedAmount()).isEqualByComparingTo(overview.getInflowTotal());
    }

    @Test
    @DisplayName(
            "불변식 ②: byCause[].amount 합 = earlyWithdrawnAmount, byCause[].accounts 합 >= earlyAccounts")
    void invariantCauseSumsMatchSummary() throws SQLException {
        seedMixed();

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);
        List<FundFlowEarlyWithdrawalCauseDTO> byCause =
                fundFlowMapper.selectEarlyWithdrawalByCause(CLOCK_DATE);

        BigDecimal amountSum =
                byCause.stream()
                        .map(FundFlowEarlyWithdrawalCauseDTO::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        long accountSum =
                byCause.stream().mapToLong(FundFlowEarlyWithdrawalCauseDTO::getAccounts).sum();
        assertThat(amountSum).isEqualByComparingTo(summary.getEarlyWithdrawnAmount());
        // 계좌 3 이 두 원인에 걸쳐서 합(4)이 전체(3)보다 크다
        assertThat(accountSum).isGreaterThanOrEqualTo(summary.getEarlyAccounts());
        assertThat(accountSum).isEqualTo(4L);
    }

    @Test
    @DisplayName("불변식 ④: retention 의 사건이 된 건의 계좌 = /early-withdrawal 분자의 계좌(둘 다 완료 인출만 센다)")
    void invariantRetentionEventAccountsMatchEarlyAccounts() throws SQLException {
        seedMixed();

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);
        long retentionEventAccounts =
                fundFlowMapper
                        .selectRetentionLots(
                                FundFlowFilterDTO.builder()
                                        .asOfDate(CLOCK_DATE.minusDays(1))
                                        .clockDate(CLOCK_DATE)
                                        .build())
                        .stream()
                        .filter(lot -> Boolean.TRUE.equals(lot.getEvent()))
                        .map(lot -> lot.getAccountId())
                        .distinct()
                        .count();

        // 계좌 2 는 취소·실패 인출만 있어 둘 다 세지 않는다. 사건 계좌는 1·3·4
        assertThat(retentionEventAccounts).isEqualTo(summary.getEarlyAccounts());
        assertThat(retentionEventAccounts).isEqualTo(3L);
    }

    @Test
    @DisplayName("불변식 ③: earlyAccounts <= finalizedAccounts")
    void invariantEarlyAccountsNotMoreThanFinalizedAccounts() throws SQLException {
        seedMixed();

        FundFlowEarlyWithdrawalSummaryRowDTO summary =
                fundFlowMapper.selectEarlyWithdrawalSummary(CLOCK_DATE);

        assertThat(summary.getEarlyAccounts()).isLessThanOrEqualTo(summary.getFinalizedAccounts());
    }

    /** 환전 한 건과 같은 번호의 left_amount 를 넣는다. finalAmount·finalAt 이 null 이면 SQL NULL. */
    private void insertLot(
            long exchangeId, Long accountId, String status, String finalAmount, String finalAt)
            throws SQLException {
        execute(
                "INSERT INTO krw_exchange (exchange_id, account_id, settlement_status, final_amount, final_at) VALUES (%d, %d, '%s', %s, %s)"
                        .formatted(
                                exchangeId,
                                accountId,
                                status,
                                finalAmount == null ? "NULL" : finalAmount,
                                finalAt == null ? "NULL" : "'" + finalAt + "'"));
        execute(
                "INSERT INTO left_amount (left_amount_id, exchange_id, cur_amount) VALUES (%d, %d, 0)"
                        .formatted(exchangeId, exchangeId));
    }

    private void insertWithdrawal(long withdrawalId, Long accountId, String status)
            throws SQLException {
        execute(
                "INSERT INTO withdrawal (withdrawal_id, account_id, requested_amount, status, processed_at) VALUES (%d, %d, 1, '%s', '2026-09-01 10:00:00')"
                        .formatted(withdrawalId, accountId, status));
    }

    /** leftAmountId(= 환전 번호)가 null 이면 수익금 배분. */
    private void insertAllocation(
            long withdrawalId, Long leftAmountId, String amount, String at, String type)
            throws SQLException {
        execute(
                "INSERT INTO withdrawal_allocation (withdrawal_id, left_amount_id, allocated_amount, withdrawal_at, type) VALUES (%d, %s, %s, '%s', '%s')"
                        .formatted(
                                withdrawalId,
                                leftAmountId == null ? "NULL" : leftAmountId.toString(),
                                amount,
                                at,
                                type));
    }

    private void insertClosure(Long accountId, String status, Long withdrawalId)
            throws SQLException {
        execute(
                "INSERT INTO account_closure_request (account_id, status, withdrawal_id) VALUES (%d, '%s', %s)"
                        .formatted(accountId, status, withdrawalId));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE account (
                        account_id BIGINT PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        created_at DATETIME NOT NULL,
                        opened_at DATETIME NULL,
                        status VARCHAR(20) NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE krw_exchange (
                        exchange_id BIGINT PRIMARY KEY,
                        account_id BIGINT NOT NULL,
                        settlement_status VARCHAR(12) NOT NULL,
                        final_amount DECIMAL(15,0) NULL,
                        final_at DATETIME NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE left_amount (
                        left_amount_id BIGINT PRIMARY KEY,
                        exchange_id BIGINT NOT NULL,
                        cur_amount DECIMAL(15,0) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE withdrawal (
                        withdrawal_id BIGINT PRIMARY KEY,
                        account_id BIGINT NOT NULL,
                        requested_amount DECIMAL(15,2) NOT NULL,
                        status VARCHAR(12) NOT NULL,
                        processed_at DATETIME NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE withdrawal_allocation (
                        allocation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        withdrawal_id BIGINT NOT NULL,
                        left_amount_id BIGINT NULL,
                        allocated_amount DECIMAL(15,2) NOT NULL,
                        withdrawal_at DATETIME NOT NULL,
                        type VARCHAR(30) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE account_closure_request (
                        closure_request_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        withdrawal_id BIGINT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE domestic_order (
                        domestic_order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        side VARCHAR(4) NOT NULL,
                        status VARCHAR(10) NOT NULL,
                        amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        fee_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        tax_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        processed_at DATETIME NOT NULL
                    )
                    """);
        }
    }
}
