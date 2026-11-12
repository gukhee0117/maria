package com.app.maria.domain.fundflow.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMaturityMonthRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMaturitySummaryRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

/** 만기 도래 스케줄 쿼리를 H2(MySQL 모드)에서 운영과 같은 SQL 로 검증한다. */
class FundFlowMaturityMapperTest {

    private static final LocalDate CLOCK_DATE = LocalDate.of(2026, 11, 18);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 9, 30, 0);
    private static final LocalDateTime LOCK_BOUNDARY = NOW.minusYears(1);

    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private SqlSession sqlSession;
    private FundFlowMapper fundFlowMapper;
    private long nextExchangeId;

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
        nextExchangeId = 1L;
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
     * 계좌 1·2 = OPENED, 3 = CLOSURE_REQUESTED, 4 = CLOSED. 대상에서 빠지는 건(가정산, 확정일 없음, 확정일이 clockDate
     * 이후, 해지 계좌)과 전량 인출된 건(cur_amount = 0)을 섞어 둔다.
     */
    private void seedMixedLots() throws SQLException {
        insertAccount(1L, "OPENED");
        insertAccount(2L, "OPENED");
        insertAccount(3L, "CLOSURE_REQUESTED");
        insertAccount(4L, "CLOSED");
        insertLot(1L, "FINALIZED", "10000000", "2025-03-24 10:00:00", "4000000"); // 이미 만기(2026-03)
        insertLot(1L, "FINALIZED", "20000000", "2026-03-24 10:00:00", "20000000"); // 묶임(2027-03)
        insertLot(1L, "FINALIZED", "5000000", "2026-03-30 10:00:00", "0"); // 전량 인출(2027-03)
        insertLot(2L, "FINALIZED", "8000000", "2026-03-31 10:00:00", "0"); // 전량 인출(2027-03)
        insertLot(2L, "FINALIZED", "7000000", "2026-05-02 10:00:00", "7000000"); // 묶임(2027-05)
        insertLot(3L, "FINALIZED", "3000000", "2026-05-20 10:00:00", "3000000"); // 해지 신청 중은 포함
        insertLot(4L, "FINALIZED", "9000000", "2026-05-21 10:00:00", "0"); // 해지 계좌 제외
        insertLot(1L, "PROVISIONAL", null, null, "1500000"); // 가정산 제외
        insertLot(
                1L, "FINALIZED", "1000000", "2026-11-18 00:00:00", "1000000"); // clockDate 당일 확정 제외
        insertLot(
                2L, "FINALIZED", "2000000", "2026-11-17 23:59:59", "2000000"); // 전날 확정 포함(2027-11)
    }

    @Test
    @DisplayName("만기월(확정일 + 1년의 월)별로 건수·계좌 수·확정 원금·남은 원금을 센다")
    void selectMaturityMonthsGroupsByMaturityMonth() throws SQLException {
        seedMixedLots();

        List<FundFlowMaturityMonthRowDTO> months = fundFlowMapper.selectMaturityMonths(CLOCK_DATE);

        assertThat(months)
                .extracting(
                        FundFlowMaturityMonthRowDTO::getMaturityYear,
                        FundFlowMaturityMonthRowDTO::getMaturityMonth,
                        FundFlowMaturityMonthRowDTO::getLots,
                        FundFlowMaturityMonthRowDTO::getAccounts)
                .containsExactly(
                        tuple(2026, 3, 1L, 1L),
                        tuple(2027, 3, 3L, 2L),
                        tuple(2027, 5, 2L, 2L),
                        tuple(2027, 11, 1L, 1L));
        assertThat(months.get(0).getPaidAmount()).isEqualByComparingTo("10000000");
        assertThat(months.get(0).getRemainingAmount()).isEqualByComparingTo("4000000");
        assertThat(months.get(1).getPaidAmount()).isEqualByComparingTo("33000000");
        assertThat(months.get(1).getRemainingAmount()).isEqualByComparingTo("20000000");
        assertThat(months.get(2).getPaidAmount()).isEqualByComparingTo("10000000");
        assertThat(months.get(2).getRemainingAmount()).isEqualByComparingTo("10000000");
        assertThat(months.get(3).getPaidAmount()).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("해지(CLOSED) 계좌만 빼고 해지 신청 중은 포함하며, 뺀 확정 원금은 따로 구한다")
    void closedAccountIsExcludedButClosureRequestedIsIncluded() throws SQLException {
        seedMixedLots();

        List<FundFlowMaturityMonthRowDTO> months = fundFlowMapper.selectMaturityMonths(CLOCK_DATE);
        BigDecimal closedPaid = fundFlowMapper.selectClosedPaidAmount(CLOCK_DATE);

        long includedLots = months.stream().mapToLong(FundFlowMaturityMonthRowDTO::getLots).sum();
        assertThat(includedLots).isEqualTo(7L); // 건수 7: CLOSED 1건·가정산·당일 확정은 제외
        assertThat(closedPaid).isEqualByComparingTo("9000000");
        // 해지 신청 중(계좌 3)의 3,000,000 은 2027-05 에 들어 있다
        assertThat(months.get(2).getPaidAmount()).isEqualByComparingTo("10000000");
    }

    @Test
    @DisplayName("묶임은 확정일 > 경계, 만기는 확정일 <= 경계이고 전량 인출된 계좌는 묶인 계좌 수에서 뺀다")
    void selectMaturitySummarySplitsLockedAndMatured() throws SQLException {
        seedMixedLots();

        FundFlowMaturitySummaryRowDTO summary =
                fundFlowMapper.selectMaturitySummary(CLOCK_DATE, LOCK_BOUNDARY);

        // 묶임: 20,000,000 + 7,000,000 + 3,000,000 + 2,000,000 (전량 인출 건은 0)
        assertThat(summary.getLockedAmount()).isEqualByComparingTo("32000000");
        // 계좌 1(2천만), 2(7백만·2백만), 3(3백만) 모두 묶인 잔액이 있다
        assertThat(summary.getLockedAccounts()).isEqualTo(3L);
        assertThat(summary.getMaturedRemainingAmount()).isEqualByComparingTo("4000000");
    }

    @Test
    @DisplayName("묶인 잔액이 전량 인출뿐인 계좌는 묶인 계좌 수에 세지 않는다")
    void accountWithOnlyFullyWithdrawnLockedLotsIsNotCounted() throws SQLException {
        insertAccount(1L, "OPENED");
        insertAccount(2L, "OPENED");
        insertLot(1L, "FINALIZED", "5000000", "2026-03-30 10:00:00", "0");
        insertLot(2L, "FINALIZED", "7000000", "2026-05-02 10:00:00", "7000000");

        FundFlowMaturitySummaryRowDTO summary =
                fundFlowMapper.selectMaturitySummary(CLOCK_DATE, LOCK_BOUNDARY);

        assertThat(summary.getLockedAccounts()).isEqualTo(1L);
        assertThat(summary.getLockedAmount()).isEqualByComparingTo("7000000");
    }

    @Test
    @DisplayName("업무시각 경계(직전·정각·직후): SQL 의 묶임 판정이 기존 plusYears(1).isAfter(now) 와 같다")
    void lockedClassificationMatchesPlusYearsRuleAtBoundary() throws SQLException {
        insertAccount(1L, "OPENED");
        LocalDateTime before = LOCK_BOUNDARY.minusSeconds(1);
        LocalDateTime exact = LOCK_BOUNDARY;
        LocalDateTime after = LOCK_BOUNDARY.plusSeconds(1);
        insertLot(1L, "FINALIZED", "1000000", SQL_TIME.format(before), "100");
        insertLot(1L, "FINALIZED", "1000000", SQL_TIME.format(exact), "200");
        insertLot(1L, "FINALIZED", "1000000", SQL_TIME.format(after), "400");

        FundFlowMaturitySummaryRowDTO summary =
                fundFlowMapper.selectMaturitySummary(CLOCK_DATE, LOCK_BOUNDARY);

        long expectedLocked = 0;
        long expectedMatured = 0;
        long[] curAmounts = {100, 200, 400};
        LocalDateTime[] finalAts = {before, exact, after};
        for (int i = 0; i < finalAts.length; i++) {
            if (finalAts[i].plusYears(1).isAfter(NOW)) {
                expectedLocked += curAmounts[i];
            } else {
                expectedMatured += curAmounts[i];
            }
        }
        assertThat(summary.getLockedAmount()).isEqualByComparingTo(String.valueOf(expectedLocked));
        assertThat(summary.getMaturedRemainingAmount())
                .isEqualByComparingTo(String.valueOf(expectedMatured));
        // 정각은 만기, 직후만 묶임
        assertThat(expectedLocked).isEqualTo(400L);
        assertThat(expectedMatured).isEqualTo(300L);
    }

    @Test
    @DisplayName("대상이 없으면 월 목록이 비고 요약 금액은 0이다")
    void emptyWhenNoLots() {
        assertThat(fundFlowMapper.selectMaturityMonths(CLOCK_DATE)).isEmpty();
        FundFlowMaturitySummaryRowDTO summary =
                fundFlowMapper.selectMaturitySummary(CLOCK_DATE, LOCK_BOUNDARY);
        assertThat(summary.getLockedAmount()).isEqualByComparingTo("0");
        assertThat(summary.getLockedAccounts()).isZero();
        assertThat(summary.getMaturedRemainingAmount()).isEqualByComparingTo("0");
        assertThat(fundFlowMapper.selectClosedPaidAmount(CLOCK_DATE)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("불변식 ①: months[].paidAmount 합 = 대상 건의 final_amount 합")
    void invariantPaidSumEqualsTargetFinalAmountSum() throws SQLException {
        seedMixedLots();

        BigDecimal paidSum =
                fundFlowMapper.selectMaturityMonths(CLOCK_DATE).stream()
                        .map(FundFlowMaturityMonthRowDTO::getPaidAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 대상 건: FINALIZED, 확정일 < clockDate, 해지 계좌 아님 = 10+20+5+8+7+3+2 (백만)
        assertThat(paidSum).isEqualByComparingTo("55000000");
    }

    @Test
    @DisplayName("불변식 ②: lockedAmount + maturedRemainingAmount = months[].remainingAmount 합")
    void invariantLockedPlusMaturedEqualsRemainingSum() throws SQLException {
        seedMixedLots();

        BigDecimal remainingSum =
                fundFlowMapper.selectMaturityMonths(CLOCK_DATE).stream()
                        .map(FundFlowMaturityMonthRowDTO::getRemainingAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        FundFlowMaturitySummaryRowDTO summary =
                fundFlowMapper.selectMaturitySummary(CLOCK_DATE, LOCK_BOUNDARY);

        assertThat(summary.getLockedAmount().add(summary.getMaturedRemainingAmount()))
                .isEqualByComparingTo(remainingSum);
    }

    @Test
    @DisplayName("불변식 ③: lockedAccounts <= 대상 계좌 수")
    void invariantLockedAccountsNotMoreThanTargetAccounts() throws SQLException {
        seedMixedLots();

        FundFlowMaturitySummaryRowDTO summary =
                fundFlowMapper.selectMaturitySummary(CLOCK_DATE, LOCK_BOUNDARY);

        // 대상 계좌 = 건이 하나라도 있는 비해지 계좌(1, 2, 3)
        assertThat(summary.getLockedAccounts()).isLessThanOrEqualTo(3L);
    }

    @Test
    @DisplayName(
            "불변식 ④: months[].paidAmount 합 + excludedClosedPaidAmount = /overview 의 inflowTotal")
    void invariantPaidSumPlusClosedEqualsOverviewInflowTotal() throws SQLException {
        seedMixedLots();

        BigDecimal paidSum =
                fundFlowMapper.selectMaturityMonths(CLOCK_DATE).stream()
                        .map(FundFlowMaturityMonthRowDTO::getPaidAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closedPaid = fundFlowMapper.selectClosedPaidAmount(CLOCK_DATE);
        FundFlowOverviewDTO overview =
                fundFlowMapper.selectOverview(
                        FundFlowFilterDTO.builder()
                                .asOfDate(CLOCK_DATE.minusDays(1))
                                .clockDate(CLOCK_DATE)
                                .build());

        assertThat(paidSum.add(closedPaid)).isEqualByComparingTo(overview.getInflowTotal());
        assertThat(overview.getInflowTotal()).isEqualByComparingTo("64000000");
    }

    private void insertAccount(Long accountId, String status) throws SQLException {
        execute(
                "INSERT INTO account (account_id, customer_id, created_at, opened_at, status) VALUES (%d, %d, '2026-03-01 09:00:00', '2026-03-02 09:00:00', '%s')"
                        .formatted(accountId, accountId, status));
    }

    /** 환전 한 건과 그 left_amount 를 넣는다. finalAmount·finalAt 이 null 이면 SQL NULL. */
    private void insertLot(
            Long accountId,
            String settlementStatus,
            String finalAmount,
            String finalAt,
            String curAmount)
            throws SQLException {
        long exchangeId = nextExchangeId++;
        execute(
                "INSERT INTO krw_exchange (exchange_id, account_id, settlement_status, final_amount, final_at) VALUES (%d, %d, '%s', %s, %s)"
                        .formatted(
                                exchangeId,
                                accountId,
                                settlementStatus,
                                finalAmount == null ? "NULL" : finalAmount,
                                finalAt == null ? "NULL" : "'" + finalAt + "'"));
        execute(
                "INSERT INTO left_amount (exchange_id, cur_amount) VALUES (%d, %s)"
                        .formatted(exchangeId, curAmount));
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
                        status VARCHAR(20) NOT NULL
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
                        left_amount_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        exchange_id BIGINT NOT NULL,
                        cur_amount DECIMAL(15,0) NOT NULL
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
