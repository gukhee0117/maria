package com.app.maria.domain.fundflow.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelStageDTO;
import com.app.maria.domain.fundflow.type.FundFlowTransactionType;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

class FundFlowMapperTest {

    private static final LocalDate CLOCK_DATE = LocalDate.of(2026, 11, 18);

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

    private FundFlowFilterDTO filter() {
        return FundFlowFilterDTO.builder()
                .asOfDate(CLOCK_DATE.minusDays(1))
                .clockDate(CLOCK_DATE)
                .build();
    }

    @Test
    @DisplayName("앞 단계를 모두 지난 고객만 다음 단계로 세고, 전환율은 소수 4자리로 계산한다")
    void selectFunnelCountsNestedStagesWithConversionRatios() throws SQLException {
        insertCustomer(1L); // 6단계 모두 도달
        insertConsent(1L, true);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertSellOrder(1L, "EXECUTED", "2026-04-10 10:00:00");
        insertDomesticOrder(1L, "BUY", "EXECUTED", "2026-04-12 10:00:00");

        insertCustomer(2L); // 계좌 신청까지
        insertConsent(2L, true);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", null);

        insertCustomer(3L); // 동의 없음: 계좌가 있어도 2단계에서 멈춘다
        insertAccount(3L, 3L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");

        insertCustomer(4L); // 첫 매도까지, 매수 없음
        insertConsent(4L, true);
        insertAccount(4L, 4L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertSellOrder(4L, "EXECUTED", "2026-04-10 10:00:00");

        insertCustomer(5L); // 개설까지, 거부된 매도는 센다
        insertConsent(5L, true);
        insertAccount(5L, 5L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertSellOrder(5L, "REJECTED", "2026-04-10 10:00:00");

        insertCustomer(6L); // 가입만
        insertConsent(6L, false);

        List<FundFlowFunnelStageDTO> result = fundFlowMapper.selectFunnel(filter());

        assertThat(result)
                .extracting(FundFlowFunnelStageDTO::getStageNo)
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(result)
                .extracting(FundFlowFunnelStageDTO::getCustomers)
                .containsExactly(6L, 4L, 4L, 3L, 2L, 1L);
        assertThat(result.get(0).getStage()).isEqualTo("웹 가입");
        assertThat(result.get(0).getConvPrev()).isNull();
        assertThat(result.get(1).getConvPrev()).isEqualByComparingTo("0.6667");
        assertThat(result.get(2).getConvPrev()).isEqualByComparingTo("1.0000");
        assertThat(result.get(3).getConvPrev()).isEqualByComparingTo("0.7500");
        assertThat(result.get(5).getConvPrev()).isEqualByComparingTo("0.5000");
        assertThat(result.get(5).getConvTotal()).isEqualByComparingTo("0.1667");
    }

    @Test
    @DisplayName("업무시계 날짜 이후에 발생한 매수·매도는 해당 단계에 세지 않는다")
    void selectFunnelExcludesEventsOnOrAfterClockDate() throws SQLException {
        insertCustomer(1L);
        insertConsent(1L, true);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertSellOrder(1L, "EXECUTED", "2026-11-17 23:59:59");
        insertDomesticOrder(1L, "BUY", "EXECUTED", "2026-11-18 00:00:00");

        List<FundFlowFunnelStageDTO> result = fundFlowMapper.selectFunnel(filter());

        assertThat(result.get(4).getCustomers()).isEqualTo(1L);
        assertThat(result.get(5).getCustomers()).isZero();
    }

    @Test
    @DisplayName("체결되지 않은 매수와 매도(SELL)는 첫 국내 매수로 세지 않는다")
    void selectFunnelCountsOnlyExecutedBuyOrders() throws SQLException {
        insertCustomer(1L);
        insertConsent(1L, true);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertSellOrder(1L, "EXECUTED", "2026-04-10 10:00:00");
        insertDomesticOrder(1L, "BUY", "REJECTED", "2026-04-12 10:00:00");
        insertDomesticOrder(1L, "SELL", "EXECUTED", "2026-04-13 10:00:00");

        List<FundFlowFunnelStageDTO> result = fundFlowMapper.selectFunnel(filter());

        assertThat(result.get(4).getCustomers()).isEqualTo(1L);
        assertThat(result.get(5).getCustomers()).isZero();
    }

    @Test
    @DisplayName("고객이 없으면 6단계 모두 0명이고 전환율은 null이다")
    void selectFunnelReturnsZeroStagesWhenNoCustomers() {
        List<FundFlowFunnelStageDTO> result = fundFlowMapper.selectFunnel(filter());

        assertThat(result).hasSize(6);
        assertThat(result)
                .allSatisfy(
                        r -> {
                            assertThat(r.getCustomers()).isZero();
                            assertThat(r.getConvPrev()).isNull();
                            assertThat(r.getConvTotal()).isNull();
                        });
    }

    @Test
    @DisplayName("개요: 확정 유입만, 수수료·세금을 뺀 체결금액으로 합산하고 업무시계 날짜 이후는 제외한다")
    void selectOverviewSumsExecutedAmountsBeforeClockDate() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", null);
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at) VALUES"
                        + " (1, 'FINALIZED', 1000000, '2026-05-01 00:00:00'),"
                        + " (1, 'PROVISIONAL', 500000, NULL),"
                        + " (1, 'FINALIZED', 700000, '2026-11-18 00:00:00')");
        insertOrder(1L, "BUY", "EXECUTED", -300500, 500, 0, "2026-06-01 10:00:00");
        insertOrder(1L, "BUY", "EXECUTED", -999999, 0, 0, "2026-11-18 09:00:00");
        insertOrder(1L, "BUY", "REJECTED", 0, 0, 0, "2026-06-02 10:00:00");
        insertOrder(1L, "SELL", "EXECUTED", 99000, 500, 500, "2026-07-01 10:00:00");

        var result = fundFlowMapper.selectOverview(filter());

        assertThat(result.getOpenedAccounts()).isEqualTo(1L);
        assertThat(result.getInflowTotal()).isEqualByComparingTo("1000000");
        assertThat(result.getDomesticBuyTotal()).isEqualByComparingTo("300000");
        assertThat(result.getDomesticSellTotal()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("개요: 기간 하한(periodFrom)이 있으면 그 날 이후 유입·매수·매도만 합산하고 개설 계좌는 누적으로 둔다")
    void selectOverviewAppliesPeriodLowerBoundToFlowsOnly() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at) VALUES"
                        + " (1, 'FINALIZED', 1000000, '2026-09-30 23:59:59'),"
                        + " (1, 'FINALIZED', 400000, '2026-10-01 00:00:00')");
        insertOrder(1L, "BUY", "EXECUTED", -100000, 0, 0, "2026-09-30 10:00:00");
        insertOrder(1L, "BUY", "EXECUTED", -200000, 0, 0, "2026-10-05 10:00:00");
        insertOrder(1L, "SELL", "EXECUTED", 50000, 0, 0, "2026-10-06 10:00:00");

        var result =
                fundFlowMapper.selectOverview(
                        filter().toBuilder().periodFrom(LocalDate.of(2026, 10, 1)).build());

        assertThat(result.getOpenedAccounts()).isEqualTo(1L);
        assertThat(result.getInflowTotal()).isEqualByComparingTo("400000");
        assertThat(result.getDomesticBuyTotal()).isEqualByComparingTo("200000");
        assertThat(result.getDomesticSellTotal()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("퍼널: 기간 하한이 있으면 그 날 이후 가입한 고객만 코호트로 센다")
    void selectFunnelCountsOnlyCustomersSignedUpInPeriod() throws SQLException {
        insertCustomer(1L);
        insertCustomer(2L);
        execute("UPDATE customer SET created_at = '2026-09-15 10:00:00' WHERE customer_id = 1");
        execute("UPDATE customer SET created_at = '2026-10-15 10:00:00' WHERE customer_id = 2");

        List<FundFlowFunnelStageDTO> all = fundFlowMapper.selectFunnel(filter());
        List<FundFlowFunnelStageDTO> october =
                fundFlowMapper.selectFunnel(
                        filter().toBuilder().periodFrom(LocalDate.of(2026, 10, 1)).build());

        assertThat(all.get(0).getCustomers()).isEqualTo(2L);
        assertThat(october.get(0).getCustomers()).isEqualTo(1L);
    }

    @Test
    @DisplayName("조정비율 구간: 매도한 계좌만, 경계값은 아래 구간에 넣지 않고 위 구간에 넣는다")
    void selectRatioBinsCountsSoldAccountsByRatio() throws SQLException {
        insertSnapshotAccount(1L, "POSSIBLE", "0", "0", "0", "2026-11-17 02:00:00"); // 매도 없음 제외
        insertSnapshotAccount(2L, "REDUCED", "100", "0", "100", "2026-11-17 02:00:00");
        insertSnapshotAccount(3L, "REDUCED", "100", "0.25", "75", "2026-11-17 02:00:00");
        insertSnapshotAccount(4L, "REDUCED", "100", "0.2499", "75", "2026-11-17 02:00:00");
        insertSnapshotAccount(5L, "REDUCED", "100", "0.9999", "1", "2026-11-17 02:00:00");
        insertSnapshotAccount(6L, "POSSIBLE", "100", "1", "0", "2026-11-17 02:00:00");

        var result = fundFlowMapper.selectRatioBins();

        assertThat(result)
                .extracting(b -> b.getCode() + ":" + b.getAccounts())
                .containsExactlyInAnyOrder(
                        "ZERO:1", "UNDER_25:1", "UNDER_50:1", "UNDER_100:1", "FULL:1");
    }

    @Test
    @DisplayName("감면액 목록: 0보다 큰 계좌만 오름차순으로 낸다")
    void selectReliefAmountsReturnsPositiveSortedAmounts() throws SQLException {
        execute(
                "INSERT INTO tax_rule (rule_type, rule_value, valid_from, valid_to) VALUES"
                        + " ('BASIC_DEDUCTION', 2500000, '2026-01-01', '9999-12-31'),"
                        + " ('TAX_RATE', 0.22, '2026-01-01', '9999-12-31')");
        // 계좌1: 일반 과세 2,200,000 - 최종 1,000,000 = 1,200,000
        execute(
                "INSERT INTO tax_snapshot VALUES (1, '2026-11-17 02:00:00', 100, 12500000, 0, 0.5, 0, 1000000)");
        // 계좌2: 일반 과세 0 → 감면 0 (제외)
        execute(
                "INSERT INTO tax_snapshot VALUES (2, '2026-11-17 02:00:00', 100, 1000000, 0, 1, 0, 0)");
        // 계좌3: 일반 과세 0.22 × (4,500,000 - 2,500,000) = 440,000 - 최종 40,000 = 400,000
        execute(
                "INSERT INTO tax_snapshot VALUES (3, '2026-11-17 02:00:00', 100, 4500000, 0, 0.5, 0, 40000)");

        var result = fundFlowMapper.selectReliefAmounts(filter());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualByComparingTo("400000");
        assertThat(result.get(1)).isEqualByComparingTo("1200000");
    }

    @Test
    @DisplayName("원금 유지 원천: 확정 건만, 1년 미경과 인출이 있으면 사건과 경과일, 없으면 업무시계까지 절단")
    void selectRetentionLotsMarksEventAndCensoring() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-03-01 10:00:00", "2026-03-02 10:00:00");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at) VALUES"
                        + " (1, 'FINALIZED', 1000000, '2026-10-01 00:00:00'),"
                        + " (1, 'FINALIZED', 2000000, '2026-10-20 00:00:00'),"
                        + " (1, 'PROVISIONAL', 500000, NULL),"
                        + " (1, 'FINALIZED', 700000, '2026-11-18 00:00:00')");
        execute("INSERT INTO left_amount (exchange_id, cur_amount) VALUES (1, 0), (2, 2000000)");
        execute(
                "INSERT INTO withdrawal_allocation (withdrawal_id, left_amount_id, allocated_amount,"
                        + " withdrawal_at, type) VALUES"
                        + " (1, 1, 1000000, '2026-10-11 09:00:00', 'IMMATURE_PRINCIPAL_INCLUDED'),"
                        + " (1, 2, 5, '2026-10-11 09:00:00', 'EARNINGS_ONLY')");

        var result = fundFlowMapper.selectRetentionLots(filter());

        assertThat(result)
                .extracting(
                        r ->
                                r.getFinalAmount().toBigInteger()
                                        + ":"
                                        + r.getDays()
                                        + ":"
                                        + r.getEvent())
                .containsExactly("1000000:10:true", "2000000:29:false");
    }

    @Test
    @DisplayName("원금 유지 원천: 기간 하한이 있으면 그 날 이후 확정된 건만 코호트로 센다")
    void selectRetentionLotsAppliesPeriodLowerBound() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-03-01 10:00:00", "2026-03-02 10:00:00");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at) VALUES"
                        + " (1, 'FINALIZED', 1000000, '2026-09-30 00:00:00'),"
                        + " (1, 'FINALIZED', 2000000, '2026-10-20 00:00:00')");

        var result =
                fundFlowMapper.selectRetentionLots(
                        filter().toBuilder().periodFrom(LocalDate.of(2026, 10, 1)).build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFinalAmount()).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("계좌별 유입·매수: 연령대와 함께 계좌 단위로 낸다 (유입이 없는 계좌는 0)")
    void selectAccountFlowsReturnsPerAccountRows() throws SQLException {
        insertCustomerWith(1L, "1990-01-01", "ACTIVE");
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomerWith(2L, "1960-01-01", "ACTIVE");
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at) VALUES"
                        + " (1, 'FINALIZED', 1000000, '2026-05-01 00:00:00')");
        insertOrder(1L, "BUY", "EXECUTED", -600500, 500, 0, "2026-06-01 10:00:00");

        var result = fundFlowMapper.selectAccountFlows(filter());

        assertThat(result)
                .extracting(
                        r ->
                                r.getAgeBand()
                                        + ":"
                                        + r.getInflow().toBigInteger()
                                        + ":"
                                        + r.getBuyExec().toBigInteger())
                .containsExactlyInAnyOrder("30대:1000000:600000", "60대 이상:0:0");
    }

    @Test
    @DisplayName("누수: 위에서부터 먼저 걸리는 하나로 분류하고 타 계좌 순매수 보유 계좌 수를 센다")
    void selectLeakageClassifiesByPriority() throws SQLException {
        insertSnapshotAccount(1L, "POSSIBLE", "0", "0", "0", "2026-11-17 02:00:00"); // NO_SELL
        insertSnapshotAccount(2L, "IMPOSSIBLE", "100", "1", "0", "2026-11-17 02:00:00"); // 취소
        insertSnapshotAccount(3L, "POSSIBLE", "100", "1", "0", "2026-11-17 02:00:00"); // 영향 없음
        insertSnapshotAccount(4L, "REDUCED", "100", "0.5", "50", "2026-11-17 02:00:00"); // 부분
        insertSnapshotAccount(5L, "REDUCED", "100", "0", "100", "2026-11-17 02:00:00"); // 소멸

        var result = fundFlowMapper.selectLeakage();

        assertThat(result)
                .extracting(c -> c.getCode() + ":" + c.getAccounts() + ":" + c.getWithExternalBuy())
                .containsExactlyInAnyOrder(
                        "NO_SELL:1:0",
                        "BENEFIT_CANCELED:1:0",
                        "NO_IMPACT:1:0",
                        "PARTIAL_REDUCED:1:1",
                        "EXTERNAL_BUY_OFFSET:1:1");
        assertThat(fundFlowMapper.selectSnapshotCalculatedAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 11, 17, 2, 0));
    }

    @Test
    @DisplayName("월별: 완료된 달은 fund_flow_monthly 합산, 진행 중인 달은 원천 예수금 순액 기준으로 집계한다")
    void selectMonthlyClosedAndPartial() throws SQLException {
        execute(
                "INSERT INTO fund_flow_monthly (account_id, flow_month, foreign_sell_amount,"
                        + " cash_balance_begin, cash_balance_end) VALUES"
                        + " (1, '2026-10-01', 100, 10, 20), (2, '2026-10-01', 200, 30, 40),"
                        + " (1, '2026-11-01', 999, 0, 0)");
        insertOrder(1L, "BUY", "EXECUTED", -1000, 10, 0, "2026-11-05 10:00:00");
        insertOrder(1L, "SELL", "EXECUTED", 400, 5, 5, "2026-11-06 10:00:00");
        insertOrder(1L, "BUY", "EXECUTED", -5000, 0, 0, "2026-10-30 10:00:00");
        execute(
                "INSERT INTO withdrawal (account_id, requested_amount, status, processed_at) VALUES"
                        + " (1, 70, 'COMPLETED', '2026-11-10 10:00:00'),"
                        + " (1, 80, 'CANCELLED', '2026-11-10 10:00:00')");

        var closed = fundFlowMapper.selectMonthlyClosed(filterWithMonthStart());
        var partial = fundFlowMapper.selectMonthlyPartial(filterWithMonthStart());

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getFlowMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(closed.get(0).getForeignSellAmount()).isEqualByComparingTo("300");
        assertThat(closed.get(0).getCashBalanceEnd()).isEqualByComparingTo("60");
        assertThat(partial.getDomesticBuyAmount()).isEqualByComparingTo("1000");
        assertThat(partial.getDomesticSellAmount()).isEqualByComparingTo("400");
        assertThat(partial.getWithdrawalAmount()).isEqualByComparingTo("70");
        assertThat(partial.getCashBalanceBegin()).isNull();
    }

    @Test
    @DisplayName("세그먼트: 기준일 만 나이로 연령대를 가른다(생일 하루 전이면 아직 한 살 어리다)")
    void selectSegmentsUsesAgeAtAsOfDate() throws SQLException {
        insertCustomerWith(1L, "1996-11-17", "ACTIVE"); // 만 30세 → 30대
        insertCustomerWith(2L, "1996-11-18", "ACTIVE"); // 만 29세 → 20대 이하
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at)"
                        + " VALUES (1, 'FINALIZED', 1000, '2026-05-01 00:00:00')");
        insertOrder(1L, "BUY", "EXECUTED", -600, 0, 0, "2026-06-01 10:00:00");

        var rows = fundFlowMapper.selectSegments(filter());

        assertThat(rows)
                .extracting(r -> r.getAgeBand() + ":" + r.getInvestorType() + ":" + r.getAccounts())
                .containsExactlyInAnyOrder("30대:ACTIVE:1", "20대 이하:ACTIVE:1");
        var thirties =
                rows.stream().filter(r -> "30대".equals(r.getAgeBand())).findFirst().orElseThrow();
        assertThat(thirties.getInflowTotal()).isEqualByComparingTo("1000");
        assertThat(thirties.getBuyTotal()).isEqualByComparingTo("600");
        assertThat(thirties.getAccountsWithInflow()).isEqualTo(1L);
    }

    @Test
    @DisplayName("감면액: 기준일 유효 세율·기본공제로 일반 과세 세액을 구하고 POSITIVE는 감면 0 계좌를 뺀다")
    void selectReliefComputesGeneralTaxAndScope() throws SQLException {
        execute(
                "INSERT INTO tax_rule (rule_type, rule_value, valid_from, valid_to) VALUES"
                        + " ('BASIC_DEDUCTION', 2500000, '2026-01-01', '9999-12-31'),"
                        + " ('TAX_RATE', 0.22, '2026-01-01', '9999-12-31'),"
                        + " ('TAX_RATE', 0.99, '2027-01-01', '9999-12-31')");
        // 계좌1: 일반 과세 = 0.22 × (12,500,000 − 2,500,000) = 2,200,000, 최종세액 1,000,000 → 감면 1,200,000
        execute(
                "INSERT INTO tax_snapshot VALUES (1, '2026-11-17 02:00:00', 100, 12500000, 0, 0.5, 0,"
                        + " 1000000)");
        // 계좌2: 양도소득이 기본공제 이하 → 일반 과세 0, 최종세액 0 → 감면 0
        execute(
                "INSERT INTO tax_snapshot VALUES (2, '2026-11-17 02:00:00', 100, 1000000, 0, 1, 0, 0)");
        insertOrder(1L, "BUY", "EXECUTED", -3000, 0, 0, "2026-06-01 10:00:00");

        var all = fundFlowMapper.selectRelief(filterWithScope("ALL"));
        var positive = fundFlowMapper.selectRelief(filterWithScope("POSITIVE"));

        assertThat(all.getAccounts()).isEqualTo(2L);
        assertThat(all.getGeneralTaxTotal()).isEqualByComparingTo("2200000");
        assertThat(all.getFinalTaxTotal()).isEqualByComparingTo("1000000");
        assertThat(all.getReliefTotal()).isEqualByComparingTo("1200000");
        assertThat(all.getAccountsWithRelief()).isEqualTo(1L);
        assertThat(all.getNetInvestTotal()).isEqualByComparingTo("3000");
        assertThat(positive.getAccounts()).isEqualTo(1L);
    }

    @Test
    @DisplayName("운용처: 보유 중인 종목만(종료 상태는 수량이 남아도 제외) 매입금액으로, 순매수는 상위 10종목을 내림차순으로 낸다")
    void selectProductCostsAndTopNetBuy() throws SQLException {
        execute(
                "INSERT INTO domestic_product (domestic_product_id, name, type, sector_name) VALUES"
                        + " (1, '삼성전자', 'STOCK', '전기전자'), (2, 'KODEX 200', 'FUND', NULL),"
                        + " (3, '카카오', 'STOCK', 'IT서비스')");
        execute(
                "INSERT INTO domestic_stock_balance (account_id, domestic_product_id, qty,"
                        + " avg_purchase_price, status) VALUES (1, 1, 10, 70000, 'HOLDING'),"
                        + " (2, 1, 5, 80000, 'PARTIALLY_SOLD'), (1, 2, 100, 30000, 'HOLDING'),"
                        + " (1, 3, 0, 50000, 'SOLD_OUT'),"
                        + " (3, 1, 7, 90000, 'SOLD_OUT'), (3, 3, 4, 90000, 'TERMINATED')");
        insertOrderFor(1L, 1L, "BUY", -10000, 0, 0);
        insertOrderFor(1L, 2L, "BUY", -50000, 0, 0);
        insertOrderFor(1L, 2L, "SELL", 20000, 0, 0);

        var costs = fundFlowMapper.selectProductCosts();
        var top = fundFlowMapper.selectTopNetBuy(filter());

        assertThat(costs).hasSize(2);
        var samsung =
                costs.stream().filter(c -> "삼성전자".equals(c.getName())).findFirst().orElseThrow();
        assertThat(samsung.getPurchaseCost()).isEqualByComparingTo("1100000"); // 10×70000 + 5×80000
        assertThat(top).extracting(n -> n.getName()).containsExactly("KODEX 200", "삼성전자");
        assertThat(top.get(0).getNetBuyExec()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("고객: 본인 계좌의 확정 유입·매수·매도·예수금을 집계하고, 계좌가 없으면 null이다")
    void selectCustomerSummaryAndMissingAccount() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute("UPDATE account SET amount = 12345 WHERE account_id = 1");
        execute(
                "INSERT INTO sell_order (account_id, status, sell_qty, base_price, processed_at)"
                        + " VALUES (1, 'EXECUTED', 10, 1500, '2026-05-01 10:00:00')");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at)"
                        + " VALUES (1, 'FINALIZED', 14000, '2026-05-03 00:00:00')");
        insertOrder(1L, "BUY", "EXECUTED", -7010, 10, 0, "2026-05-10 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", null);

        var mine = fundFlowMapper.selectCustomerSummary(1L, CLOCK_DATE);

        assertThat(mine.isHasAccount()).isTrue();
        assertThat(mine.getSoldForeignAmount()).isEqualByComparingTo("15000");
        assertThat(mine.getKrwInflowAmount()).isEqualByComparingTo("14000");
        assertThat(mine.getDomesticBuyAmount()).isEqualByComparingTo("7000");
        assertThat(mine.getCashBalance()).isEqualByComparingTo("12345");
        assertThat(fundFlowMapper.selectCustomerSummary(2L, CLOCK_DATE)).isNull();
        assertThat(fundFlowMapper.selectCustomerSummary(99L, CLOCK_DATE)).isNull();
    }

    @Test
    @DisplayName("고객: 월별 흐름은 본인 계좌의 완료된 달만 월 오름차순으로 내고 업무시계 달 이후는 제외한다")
    void selectCustomerMonthlyReturnsOnlyOwnClosedMonthsInOrder() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute(
                "INSERT INTO fund_flow_monthly (account_id, flow_month, foreign_sell_amount,"
                        + " calculated_at, run_id) VALUES"
                        + " (1, '2026-05-01', 2, '2026-06-01 00:00:00', 'r'),"
                        + " (1, '2026-04-01', 1, '2026-05-01 00:00:00', 'r'),"
                        + " (1, '2026-11-01', 5, '2026-11-02 00:00:00', 'r'),"
                        + " (1, '2026-12-01', 7, '2026-12-02 00:00:00', 'r'),"
                        + " (2, '2026-04-01', 99, '2026-05-01 00:00:00', 'r')");

        var months = fundFlowMapper.selectCustomerMonthly(1L, LocalDate.of(2026, 11, 1));

        assertThat(months)
                .extracting(m -> m.getFlowMonth().toString())
                .containsExactly("2026-04-01", "2026-05-01");
    }

    @Test
    @DisplayName("고객: 진행 중인 달은 본인 계좌의 월초~업무시계 전일 원천 거래만 집계하고, 개설 계좌가 없으면 null이다")
    void selectCustomerMonthlyPartialAggregatesOwnAccountOnly() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomer(3L);
        insertAccount(3L, 3L, "2026-04-01 10:00:00", null);
        insertOrder(1L, "BUY", "EXECUTED", -1000, 10, 0, "2026-11-05 10:00:00");
        insertOrder(1L, "SELL", "EXECUTED", 400, 5, 5, "2026-11-06 10:00:00");
        insertOrder(1L, "BUY", "EXECUTED", -5000, 0, 0, "2026-10-30 10:00:00"); // 지난달
        insertOrder(1L, "BUY", "EXECUTED", -7000, 0, 0, "2026-11-18 09:00:00"); // 업무시계 당일
        insertOrder(2L, "BUY", "EXECUTED", -9000, 0, 0, "2026-11-05 10:00:00"); // 남의 계좌
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at)"
                        + " VALUES (1, 'FINALIZED', 2000, '2026-11-03 00:00:00'),"
                        + " (1, 'PROVISIONAL', 3000, NULL)");
        execute(
                "INSERT INTO withdrawal (account_id, requested_amount, status, processed_at)"
                        + " VALUES (1, 70, 'COMPLETED', '2026-11-10 10:00:00'),"
                        + " (1, 80, 'CANCELLED', '2026-11-10 10:00:00')");

        var mine =
                fundFlowMapper.selectCustomerMonthlyPartial(
                        1L, CLOCK_DATE.withDayOfMonth(1), CLOCK_DATE);

        assertThat(mine.getDomesticBuyAmount()).isEqualByComparingTo("1000");
        assertThat(mine.getDomesticSellAmount()).isEqualByComparingTo("400");
        assertThat(mine.getKrwInflowAmount()).isEqualByComparingTo("2000");
        assertThat(mine.getWithdrawalAmount()).isEqualByComparingTo("70");
        assertThat(
                        fundFlowMapper.selectCustomerMonthlyPartial(
                                3L, CLOCK_DATE.withDayOfMonth(1), CLOCK_DATE))
                .isNull();
    }

    @Test
    @DisplayName("고객 운용처: 본인 개설 계좌의 보유 종목만, 종료 상태는 수량이 남아도 제외한다")
    void selectCustomerProductCostsExcludesEndedStatuses() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute("UPDATE account SET status = 'OPENED' WHERE account_id = 1");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute("UPDATE account SET status = 'OPENED' WHERE account_id = 2");
        execute(
                "INSERT INTO domestic_product (domestic_product_id, ticker, name, type, sector_name)"
                        + " VALUES (1, 'A', '삼성전자', 'STOCK', '전기전자'),"
                        + " (2, 'B', '카카오', 'STOCK', 'IT서비스')");
        execute(
                "INSERT INTO domestic_stock_balance (account_id, domestic_product_id, qty,"
                        + " avg_purchase_price, status, last_purchase_date) VALUES"
                        + " (1, 1, 10, 70000, 'HOLDING', '2026-06-01 00:00:00'),"
                        + " (1, 2, 4, 50000, 'SOLD_OUT', '2026-06-01 00:00:00'),"
                        + " (1, 2, 3, 50000, 'TERMINATED', '2026-06-01 00:00:00'),"
                        + " (2, 1, 99, 70000, 'HOLDING', '2026-06-01 00:00:00')");

        var costs = fundFlowMapper.selectCustomerProductCosts(1L);

        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).getName()).isEqualTo("삼성전자");
        assertThat(costs.get(0).getPurchaseCost()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("고객: 예상 혜택은 스냅샷과 기준일 세율로 일반 과세 세액을 채우고, 스냅샷이 없으면 스냅샷 값만 null이다")
    void selectCustomerReliefWithAndWithoutSnapshot() throws SQLException {
        execute(
                "INSERT INTO tax_rule (rule_type, rule_value, valid_from, valid_to) VALUES"
                        + " ('BASIC_DEDUCTION', 2500000, '2026-01-01', '9999-12-31'),"
                        + " ('TAX_RATE', 0.22, '2026-01-01', '9999-12-31')");
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute("UPDATE account SET benefit = 'REDUCED' WHERE account_id = 1");
        execute(
                "INSERT INTO tax_snapshot VALUES (1, '2026-11-17 02:00:00', 100, 12500000, 50, 0.5, 0,"
                        + " 1000000)");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");

        var withSnapshot = fundFlowMapper.selectCustomerRelief(1L, LocalDate.of(2026, 11, 17));
        var withoutSnapshot = fundFlowMapper.selectCustomerRelief(2L, LocalDate.of(2026, 11, 17));

        assertThat(withSnapshot.getBenefit()).isEqualTo("REDUCED");
        assertThat(withSnapshot.getGeneralTax()).isEqualByComparingTo("2200000");
        assertThat(withSnapshot.getAdjustRatio()).isEqualByComparingTo("0.5");
        assertThat(withoutSnapshot.isHasAccount()).isTrue();
        assertThat(withoutSnapshot.getWeightedSell()).isNull();
        assertThat(fundFlowMapper.selectCustomerRelief(99L, LocalDate.of(2026, 11, 17))).isNull();
    }

    @Test
    @DisplayName("고객: 개설된 본인 계좌가 있을 때만 true다(신청만 한 계좌·계좌 없는 고객은 false)")
    void existsCustomerOpenedAccount() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", null);
        insertCustomer(3L);

        assertThat(fundFlowMapper.existsCustomerOpenedAccount(1L)).isTrue();
        assertThat(fundFlowMapper.existsCustomerOpenedAccount(2L)).isFalse();
        assertThat(fundFlowMapper.existsCustomerOpenedAccount(3L)).isFalse();
    }

    @Test
    @DisplayName("고객: 거래 행은 확정된 원화·체결된 주문·완료된 인출만, 업무시계 날짜 미만·본인 계좌만 낸다")
    void selectCustomerTransactionRowsFiltersAndSigns() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute(
                "INSERT INTO domestic_product (domestic_product_id, name, type) VALUES"
                        + " (10, '삼성전자', 'STOCK'), (11, 'KODEX 200', 'FUND')");
        execute(
                "INSERT INTO krw_exchange (account_id, settlement_status, final_amount, final_at) VALUES"
                        + " (1, 'FINALIZED', 1000000, '2026-05-19 00:00:00'),"
                        + " (1, 'FINALIZED', 500000, '2026-05-19 00:00:00'),"
                        + " (1, 'PROVISIONAL', 300000, NULL),"
                        + " (1, 'FINALIZED', 700000, '2026-11-18 00:00:00'),"
                        + " (2, 'FINALIZED', 999999, '2026-05-19 00:00:00')");
        // 패키지가 아닌 생성기 체결(stock_package_id NULL)과 패키지 체결을 모두 포함한다
        insertTransactionOrder(
                1L,
                10L,
                "BUY",
                "EXECUTED",
                "5",
                -300500,
                500,
                0,
                "GENERATOR",
                null,
                "2026-06-01 10:00:00");
        insertTransactionOrder(
                1L,
                11L,
                "BUY",
                "EXECUTED",
                "2",
                -100200,
                200,
                0,
                "CUSTOMER",
                7L,
                "2026-06-01 11:00:00");
        insertTransactionOrder(
                1L,
                10L,
                "SELL",
                "EXECUTED",
                "1",
                59000,
                500,
                500,
                "CUSTOMER",
                7L,
                "2026-06-02 10:00:00");
        insertTransactionOrder(
                1L, 10L, "BUY", "REJECTED", "1", 0, 0, 0, "CUSTOMER", 7L, "2026-06-03 10:00:00");
        insertTransactionOrder(
                1L,
                10L,
                "BUY",
                "EXECUTED",
                "1",
                -99999,
                0,
                0,
                "CUSTOMER",
                7L,
                "2026-11-18 00:00:00");
        insertTransactionOrder(
                2L,
                10L,
                "BUY",
                "EXECUTED",
                "1",
                -88888,
                0,
                0,
                "CUSTOMER",
                7L,
                "2026-06-01 10:00:00");
        execute(
                "INSERT INTO withdrawal (account_id, requested_amount, status, processed_at) VALUES"
                        + " (1, 200000, 'COMPLETED', '2026-07-01 10:00:00'),"
                        + " (1, 300000, 'REQUESTED', '2026-07-02 10:00:00'),"
                        + " (2, 400000, 'COMPLETED', '2026-07-01 10:00:00')");

        execute(
                "UPDATE domestic_order SET price = 60100 WHERE account_id = 1 AND domestic_product_id = 10");

        var rows = fundFlowMapper.selectCustomerTransactionRows(1L, CLOCK_DATE);

        assertThat(rows).hasSize(6);
        var krw =
                rows.stream()
                        .filter(r -> r.getType() == FundFlowTransactionType.KRW_CONFIRMED)
                        .toList();
        assertThat(krw).hasSize(2);
        assertThat(krw)
                .allSatisfy(r -> assertThat(r.getEventDate()).isEqualTo(LocalDate.of(2026, 5, 19)));
        assertThat(krw.stream().map(r -> r.getCashAmount()).reduce(java.math.BigDecimal::add).get())
                .isEqualByComparingTo("1500000");

        var buys =
                rows.stream()
                        .filter(r -> r.getType() == FundFlowTransactionType.DOMESTIC_BUY)
                        .toList();
        assertThat(buys).hasSize(2);
        var samsung = buys.stream().filter(r -> r.getDomesticProductId() == 10L).findFirst().get();
        assertThat(samsung.getProductName()).isEqualTo("삼성전자");
        assertThat(samsung.getQty()).isEqualByComparingTo("5");
        assertThat(samsung.getAmount()).isEqualByComparingTo("300000"); // 체결금액 = |amount| - 수수료
        assertThat(samsung.getFeeAmount()).isEqualByComparingTo("500");
        assertThat(samsung.getCashAmount()).isEqualByComparingTo("-300500"); // 수수료 포함 출금
        assertThat(samsung.getPrice()).isEqualByComparingTo("60100"); // 평균 매입가 재계산용 체결가
        assertThat(samsung.getEventAt()).isNotNull(); // 같은 날 안의 실제 순서용 시각
        assertThat(krw.get(0).getPrice()).isNull();

        var sell =
                rows.stream()
                        .filter(r -> r.getType() == FundFlowTransactionType.DOMESTIC_SELL)
                        .findFirst()
                        .get();
        assertThat(sell.getAmount()).isEqualByComparingTo("60000"); // 체결금액 = amount + 수수료 + 거래세
        assertThat(sell.getTaxAmount()).isEqualByComparingTo("500");
        assertThat(sell.getCashAmount()).isEqualByComparingTo("59000"); // 수수료·거래세를 뺀 순액 입금

        var withdrawal =
                rows.stream()
                        .filter(r -> r.getType() == FundFlowTransactionType.WITHDRAWAL)
                        .findFirst()
                        .get();
        assertThat(withdrawal.getEventDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(withdrawal.getCashAmount()).isEqualByComparingTo("-200000");
    }

    @Test
    @DisplayName("고객: 거래가 하나도 없으면 빈 목록이다")
    void selectCustomerTransactionRowsEmpty() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-04-01 10:00:00", "2026-04-02 10:00:00");

        assertThat(fundFlowMapper.selectCustomerTransactionRows(1L, CLOCK_DATE)).isEmpty();
    }

    @Test
    @DisplayName("고객: 납입건 일정은 인출이 있는 건·전량 인출된 건을 포함하고, 남은 원금은 인출 패키지의 availability 조회와 같다")
    void selectCustomerWithdrawalLotsMatchesAvailabilityLeftAmounts() throws SQLException {
        insertCustomer(1L);
        insertAccount(1L, 1L, "2026-03-01 10:00:00", "2026-03-02 10:00:00");
        insertCustomer(2L);
        insertAccount(2L, 2L, "2026-03-01 10:00:00", "2026-03-02 10:00:00");
        execute(
                "INSERT INTO krw_exchange (exchange_id, account_id, settlement_status, final_amount, final_at)"
                        + " VALUES"
                        + " (1, 1, 'FINALIZED', 30000000, '2026-04-28 00:00:00'),"
                        + " (2, 1, 'FINALIZED', 10000000, '2026-06-08 00:00:00'),"
                        + " (3, 1, 'FINALIZED', 5000000, '2026-06-08 00:00:00'),"
                        + " (4, 1, 'FINALIZED', 7000000, '2026-03-12 00:00:00'),"
                        + " (5, 1, 'PROVISIONAL', 999, NULL),"
                        + " (6, 2, 'FINALIZED', 1111, '2026-04-28 00:00:00')");
        // 1번 일부 인출(2,000,000), 4번 전량 인출, 2·3번 그대로
        execute(
                "INSERT INTO left_amount (left_amount_id, exchange_id, cur_amount) VALUES"
                        + " (11, 1, 28000000), (12, 2, 10000000), (13, 3, 5000000),"
                        + " (14, 4, 0), (16, 6, 1111)");
        execute(
                "INSERT INTO withdrawal_allocation (withdrawal_id, left_amount_id, allocated_amount,"
                        + " withdrawal_at, type) VALUES"
                        + " (1, 11, 2000000, '2026-09-01 10:00:00', 'MATURED_PRINCIPAL_INCLUDED'),"
                        + " (1, 14, 7000000, '2026-09-01 10:00:00', 'MATURED_PRINCIPAL_INCLUDED'),"
                        + " (1, NULL, 500000, '2026-09-01 10:00:00', 'EARNINGS_ONLY')");

        var lots = fundFlowMapper.selectCustomerWithdrawalLots(1L);

        // 납입일 오름차순, 전량 인출된 건(4번)도 포함, 가환전·타 고객 건은 제외
        assertThat(lots).extracting(l -> l.getExchangeId()).containsExactly(4L, 1L, 2L, 3L);
        var fullyWithdrawn = lots.get(0);
        assertThat(fullyWithdrawn.getPaidAmount()).isEqualByComparingTo("7000000");
        assertThat(fullyWithdrawn.getRemainingAmount()).isEqualByComparingTo("0");
        assertThat(fullyWithdrawn.getWithdrawnAmount()).isEqualByComparingTo("7000000");
        var partial = lots.get(1);
        assertThat(partial.getPaidAmount()).isEqualByComparingTo("30000000");
        assertThat(partial.getRemainingAmount()).isEqualByComparingTo("28000000");
        assertThat(partial.getWithdrawnAmount()).isEqualByComparingTo("2000000");
        // 처음 납입액 = 남은 원금 + 인출 배분액 (EARNINGS_ONLY 배분은 납입건에 속하지 않는다)
        assertThat(lots)
                .allSatisfy(
                        l ->
                                assertThat(l.getPaidAmount())
                                        .isEqualByComparingTo(
                                                l.getRemainingAmount()
                                                        .add(l.getWithdrawnAmount())));

        // 인출 패키지(availability)가 쓰는 남은 원금 조회와 대조: 남은 원금이 있는 건은 같은 금액·같은 납입일시
        var available =
                sqlSession
                        .getMapper(WithdrawalMapper.class)
                        .selectAvailableLeftAmountsByAccountId(1L);
        var remainingLots = lots.stream().filter(l -> l.getRemainingAmount().signum() > 0).toList();
        assertThat(available).hasSameSizeAs(remainingLots);
        for (int i = 0; i < available.size(); i++) {
            assertThat(available.get(i).getExchangeId())
                    .isEqualTo(remainingLots.get(i).getExchangeId());
            assertThat(available.get(i).getCurAmount())
                    .isEqualByComparingTo(remainingLots.get(i).getRemainingAmount());
            assertThat(available.get(i).getFinalAt()).isEqualTo(remainingLots.get(i).getFinalAt());
        }
        assertThat(
                        available.stream()
                                .map(a -> a.getCurAmount())
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo("43000000");
    }

    @Test
    @DisplayName("스키마: 테스트용 테이블의 모든 컬럼이 db/01_ria_ddl.sql 에 같은 타입으로 있다")
    void fixtureSchemaMatchesCheckedInDdl() throws Exception {
        String ddl = Files.readString(Path.of("db/01_ria_ddl.sql"));
        String url = "jdbc:h2:mem:fundflow_ddl_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        try (Connection real = DriverManager.getConnection(url, "sa", "")) {
            for (String table : FIXTURE_TABLES) {
                var matcher =
                        Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\) ENGINE=.*?;")
                                .matcher(ddl);
                assertThat(matcher.find()).as("원본 DDL 테이블: %s", table).isTrue();
                try (Statement statement = real.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                    statement.execute(matcher.group().replaceFirst("(?s)\\s+ENGINE=.*$", ";"));
                }
            }
            String query =
                    "SELECT table_name, column_name, data_type FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name IN (%s)"
                                    .formatted(
                                            FIXTURE_TABLES.stream()
                                                    .map(t -> "'" + t + "'")
                                                    .collect(Collectors.joining(",")));
            Set<String> ddlColumns = columnSet(real, query);
            try (Connection fixture = dataSource.getConnection()) {
                Set<String> fixtureColumns = columnSet(fixture, query);
                assertThat(ddlColumns).containsAll(fixtureColumns);
            }
        }
    }

    private static Set<String> columnSet(Connection connection, String query) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery(query)) {
            while (rs.next()) {
                columns.add(rs.getString(1) + "." + rs.getString(2) + ":" + rs.getString(3));
            }
        }
        return columns;
    }

    // ---- schema / fixtures ----

    private static final List<String> FIXTURE_TABLES =
            List.of(
                    "customer",
                    "customer_consent",
                    "account",
                    "sell_order",
                    "domestic_order",
                    "krw_exchange",
                    "left_amount",
                    "withdrawal_allocation",
                    "withdrawal",
                    "fund_flow_monthly",
                    "tax_snapshot",
                    "tax_rule",
                    "domestic_product",
                    "domestic_stock_balance");

    private FundFlowFilterDTO filterWithMonthStart() {
        return filter().toBuilder().monthStart(CLOCK_DATE.withDayOfMonth(1)).build();
    }

    private FundFlowFilterDTO filterWithScope(String scope) {
        return filter().toBuilder().scope(scope).build();
    }

    private void insertCustomerWith(Long customerId, String birthDate, String investorType)
            throws SQLException {
        execute(
                ("INSERT INTO customer (customer_id, birth_date, investor_type, created_at)"
                                + " VALUES (%d, '%s', '%s', '2026-03-01 09:00:00')")
                        .formatted(customerId, birthDate, investorType));
    }

    private void insertSnapshotAccount(
            Long accountId,
            String benefit,
            String weightedSell,
            String adjustRatio,
            String externalAmount,
            String calculatedAt)
            throws SQLException {
        insertCustomer(accountId);
        insertAccount(accountId, accountId, "2026-04-01 10:00:00", "2026-04-02 10:00:00");
        execute(
                "UPDATE account SET benefit = '%s' WHERE account_id = %d"
                        .formatted(benefit, accountId));
        execute(
                ("INSERT INTO tax_snapshot VALUES (%d, '%s', %s, 0, %s, %s, 0, 0)")
                        .formatted(
                                accountId,
                                calculatedAt,
                                weightedSell,
                                externalAmount,
                                adjustRatio));
    }

    private void insertOrder(
            Long accountId,
            String side,
            String status,
            long amount,
            long fee,
            long tax,
            String processedAt)
            throws SQLException {
        execute(
                ("INSERT INTO domestic_order (account_id, side, status, amount, fee_amount, tax_amount,"
                                + " processed_at) VALUES (%d, '%s', '%s', %d, %d, %d, '%s')")
                        .formatted(accountId, side, status, amount, fee, tax, processedAt));
    }

    private void insertOrderFor(
            Long accountId, Long productId, String side, long amount, long fee, long tax)
            throws SQLException {
        execute(
                ("INSERT INTO domestic_order (account_id, domestic_product_id, side, status, amount,"
                                + " fee_amount, tax_amount, processed_at)"
                                + " VALUES (%d, %d, '%s', 'EXECUTED', %d, %d, %d, '2026-06-01 10:00:00')")
                        .formatted(accountId, productId, side, amount, fee, tax));
    }

    private void insertTransactionOrder(
            Long accountId,
            Long productId,
            String side,
            String status,
            String qty,
            long amount,
            long fee,
            long tax,
            String channel,
            Long stockPackageId,
            String processedAt)
            throws SQLException {
        execute(
                ("INSERT INTO domestic_order (account_id, domestic_product_id, stock_package_id, side, status,"
                                + " qty, amount, fee_amount, tax_amount, channel, processed_at)"
                                + " VALUES (%d, %d, %s, '%s', '%s', %s, %d, %d, %d, '%s', '%s')")
                        .formatted(
                                accountId,
                                productId,
                                stockPackageId == null ? "NULL" : stockPackageId.toString(),
                                side,
                                status,
                                qty,
                                amount,
                                fee,
                                tax,
                                channel,
                                processedAt));
    }

    private void insertCustomer(Long customerId) throws SQLException {
        execute(
                "INSERT INTO customer (customer_id, created_at) VALUES (%d, '2026-03-01 09:00:00')"
                        .formatted(customerId));
    }

    private void insertConsent(Long customerId, boolean agreed) throws SQLException {
        execute(
                """
                INSERT INTO customer_consent (customer_id, consent_type, agreed, changed_at)
                VALUES (%d, 'MYDATA_PROVISION', %s, '2026-03-01 09:05:00')
                """
                        .formatted(customerId, agreed));
    }

    private void insertAccount(Long accountId, Long customerId, String createdAt, String openedAt)
            throws SQLException {
        execute(
                "INSERT INTO account (account_id, customer_id, created_at, opened_at) VALUES (%d, %d, '%s', %s)"
                        .formatted(
                                accountId,
                                customerId,
                                createdAt,
                                openedAt == null ? "NULL" : "'" + openedAt + "'"));
    }

    private void insertSellOrder(Long accountId, String status, String processedAt)
            throws SQLException {
        execute(
                "INSERT INTO sell_order (account_id, status, processed_at) VALUES (%d, '%s', '%s')"
                        .formatted(accountId, status, processedAt));
    }

    private void insertDomesticOrder(Long accountId, String side, String status, String processedAt)
            throws SQLException {
        execute(
                "INSERT INTO domestic_order (account_id, side, status, processed_at) VALUES (%d, '%s', '%s', '%s')"
                        .formatted(accountId, side, status, processedAt));
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
                    CREATE TABLE customer (
                        customer_id BIGINT PRIMARY KEY,
                        birth_date DATE NOT NULL DEFAULT '1990-06-01',
                        investor_type VARCHAR(20) NOT NULL DEFAULT 'NEUTRAL',
                        created_at DATETIME NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE customer_consent (
                        customer_consent_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        customer_id BIGINT NOT NULL,
                        consent_type VARCHAR(30) NOT NULL,
                        agreed BOOLEAN NOT NULL,
                        changed_at DATETIME NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE account (
                        account_id BIGINT PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        created_at DATETIME NOT NULL,
                        opened_at DATETIME NULL,
                        amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        benefit VARCHAR(12) NULL,
                        status VARCHAR(20) NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE sell_order (
                        order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        status VARCHAR(10) NOT NULL,
                        sell_qty DECIMAL(15,4) NOT NULL DEFAULT 0,
                        base_price DECIMAL(15,4) NOT NULL DEFAULT 0,
                        processed_at DATETIME NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE domestic_order (
                        domestic_order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        domestic_product_id BIGINT NOT NULL DEFAULT 1,
                        stock_package_id BIGINT NULL,
                        side VARCHAR(4) NOT NULL,
                        qty DECIMAL(15,4) NOT NULL DEFAULT 0,
                        price DECIMAL(15,4) NULL,
                        status VARCHAR(10) NOT NULL,
                        amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        fee_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        tax_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        channel VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER',
                        processed_at DATETIME NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE krw_exchange (
                        exchange_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        settlement_status VARCHAR(12) NOT NULL,
                        final_amount DECIMAL(15,0) NULL,
                        final_at DATETIME NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE withdrawal (
                        withdrawal_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        account_id BIGINT NOT NULL,
                        requested_amount DECIMAL(15,2) NOT NULL,
                        status VARCHAR(12) NOT NULL,
                        processed_at DATETIME NOT NULL
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
                    CREATE TABLE fund_flow_monthly (
                        account_id BIGINT NOT NULL,
                        flow_month DATE NOT NULL,
                        foreign_sell_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        krw_inflow_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        domestic_buy_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        domestic_sell_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        withdrawal_amount DECIMAL(15,0) NOT NULL DEFAULT 0,
                        cash_balance_begin DECIMAL(15,0) NOT NULL DEFAULT 0,
                        cash_balance_end DECIMAL(15,0) NOT NULL DEFAULT 0,
                        calculated_at DATETIME NOT NULL DEFAULT '2026-01-01 00:00:00',
                        run_id VARCHAR(50) NOT NULL DEFAULT 'test'
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE tax_snapshot (
                        account_id BIGINT PRIMARY KEY,
                        calculated_at DATETIME NOT NULL,
                        weighted_sell DECIMAL(15,2) NOT NULL,
                        original_gain_amount DECIMAL(15,2) NOT NULL,
                        weighted_external_amount DECIMAL(15,2) NOT NULL,
                        adjust_ratio DECIMAL(7,4) NOT NULL,
                        final_deduction DECIMAL(15,2) NOT NULL,
                        final_tax DECIMAL(15,2) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE tax_rule (
                        rule_type VARCHAR(20) NOT NULL,
                        rule_value DECIMAL(15,4) NOT NULL,
                        valid_from DATE NOT NULL,
                        valid_to DATE NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE domestic_product (
                        domestic_product_id BIGINT PRIMARY KEY,
                        ticker VARCHAR(20) NOT NULL DEFAULT 'T',
                        name VARCHAR(100) NOT NULL,
                        type VARCHAR(15) NOT NULL,
                        sector_name VARCHAR(50) NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE domestic_stock_balance (
                        account_id BIGINT NOT NULL,
                        domestic_product_id BIGINT NOT NULL,
                        qty DECIMAL(15,4) NOT NULL,
                        avg_purchase_price DECIMAL(15,4) NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
                        last_purchase_date DATETIME NOT NULL DEFAULT '2026-01-01 00:00:00'
                    )
                    """);
        }
    }
}
