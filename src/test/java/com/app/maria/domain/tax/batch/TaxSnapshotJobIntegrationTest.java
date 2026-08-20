package com.app.maria.domain.tax.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * taxSnapshotJob을 실제 Spring Batch로 끝까지 돌려본다.
 *
 * <p>컴포넌트 단위 테스트(Reader/Processor/Writer)는 이미 Mockito로 검증했다. 여기서만 확인할 수 있는 것: (1)
 * TaxSnapshotJobConfig의 @Qualifier("transactionManager")가 SettlementJobConfig의
 * ResourcelessTransactionManager와 충돌 없이 뜨는지, (2) 실제 keyset 페이징이 페이지 경계를 넘겨도 전부 처리하는지, (3) skip이
 * Spring Batch Step 안에서 실제로 동작하는지.
 *
 * <p>real MariaDB/Redis 대신 H2로 격리한다({@code application-batchtest.yml}).
 */
@SpringBootTest
@ActiveProfiles("batchtest")
class TaxSnapshotJobIntegrationTest {

    @Autowired private JobLauncher jobLauncher;

    @Qualifier("taxSnapshotJob")
    @Autowired
    private Job taxSnapshotJob;

    @Autowired private DataSource dataSource;

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM tax_snapshot");
            statement.execute("DELETE FROM target_product_judgement");
            statement.execute("DELETE FROM krw_exchange");
            statement.execute("DELETE FROM sell_order");
            statement.execute("DELETE FROM inbound_detail");
            statement.execute("DELETE FROM account_benefit_log");
            statement.execute("DELETE FROM account");
            statement.execute("DELETE FROM customer");
            statement.execute("DELETE FROM tax_rule");
            insertFullYearReliefRates(statement);
        }
    }

    /** RELIEF_RATE가 1~5월/6~7월/8~12월로 연중 빈틈없이 커버된 정상 세율표. */
    private void insertFullYearReliefRates(Statement statement) throws SQLException {
        statement.execute(
                """
                INSERT INTO tax_rule (rule_type, rule_value, valid_from, valid_to) VALUES
                ('RELIEF_RATE',    100.0000, '2026-01-01', '2026-05-31'),
                ('RELIEF_RATE',     80.0000, '2026-06-01', '2026-07-31'),
                ('RELIEF_RATE',     50.0000, '2026-08-01', '2026-12-31'),
                ('BASIC_DEDUCTION', 2500000.0000, '2026-01-01', '9999-12-31'),
                ('TAX_RATE',        0.2200, '2026-01-01', '9999-12-31')
                """);
    }

    private Long insertOpenedAccount(String ciHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO customer (name, birth_date, investor_type, ci_hash)
                    VALUES ('테스트고객', '1990-01-01', 'NEUTRAL', '%s')
                    """
                            .formatted(ciHash),
                    Statement.RETURN_GENERATED_KEYS);
            Long customerId = generatedId(statement);

            statement.executeUpdate(
                    """
                    INSERT INTO account (customer_id, status, opened_at, benefit)
                    VALUES (%d, 'OPENED', '2026-01-01 00:00:00', 'POSSIBLE')
                    """
                            .formatted(customerId),
                    Statement.RETURN_GENERATED_KEYS);
            return generatedId(statement);
        }
    }

    private void insertFinalizedLot(
            Long accountId, LocalDateTime sellAt, String finalAmount, String purchasePrice) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO inbound_detail (purchase_price, purchase_fx_rate) VALUES (%s, 1300.0000)"
                            .formatted(purchasePrice),
                    Statement.RETURN_GENERATED_KEYS);
            Long inboundDetailId = generatedId(statement);

            statement.executeUpdate(
                    """
                    INSERT INTO sell_order (inbound_detail_id, sell_qty, base_price, status, processed_at)
                    VALUES (%d, 100.0000, 200000.0000, 'EXECUTED', '%s')
                    """
                            .formatted(inboundDetailId, sellAt),
                    Statement.RETURN_GENERATED_KEYS);
            Long orderId = generatedId(statement);

            statement.executeUpdate(
                    """
                    INSERT INTO krw_exchange (account_id, order_id, final_amount, final_at, settlement_status)
                    VALUES (%d, %d, %s, '%s', 'FINALIZED')
                    """
                            .formatted(accountId, orderId, finalAmount, sellAt));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final AtomicLong MYDATA_TRADE_ID = new AtomicLong(1);

    private void insertExternalBuy(
            String ciHash, LocalDateTime tradeDate, String netBuyAmount, LocalDateTime judgedAt) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO target_product_judgement
                        (mydata_trade_id, ci_hash, is_target, judged_at, trade_date, net_buy_amount)
                    VALUES (%d, '%s', true, '%s', '%s', %s)
                    """
                            .formatted(
                                    MYDATA_TRADE_ID.getAndIncrement(),
                                    ciHash,
                                    judgedAt,
                                    tradeDate.toLocalDate(),
                                    netBuyAmount));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Long generatedId(Statement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private JobParameters jobParameters() {
        return new JobParametersBuilder()
                .addLocalDateTime("calculatedAt", LocalDateTime.of(2026, 8, 14, 2, 0))
                .addString("runId", UUID.randomUUID().toString())
                .toJobParameters();
    }

    private BigDecimal snapshotColumn(Long accountId, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT "
                                        + column
                                        + " FROM tax_snapshot WHERE account_id = "
                                        + accountId)) {
            assertThat(rs.next()).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private int countSnapshots() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM tax_snapshot")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("계좌 여러 개를 정상 실행하면 각자 계산값 그대로 스냅샷이 저장된다")
    void job_정상실행_계좌별로_정확한_스냅샷을_남긴다() throws Exception {
        Long accountA = insertOpenedAccount("a".repeat(64));
        insertFinalizedLot(accountA, LocalDateTime.of(2026, 3, 10, 10, 0), "24000000", "150");
        Long accountB = insertOpenedAccount("b".repeat(64));
        insertFinalizedLot(accountB, LocalDateTime.of(2026, 6, 15, 10, 0), "13000000", "200");

        JobExecution execution = jobLauncher.run(taxSnapshotJob, jobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countSnapshots()).isEqualTo(2);
        // A: 매도 24,000,000 - 취득(150*1300*100)=19,500,000 → 양도소득 4,500,000, 1~5월 가중치 100%
        assertThat(snapshotColumn(accountA, "weighted_sell")).isEqualByComparingTo("24000000.00");
        assertThat(snapshotColumn(accountA, "original_gain_amount"))
                .isEqualByComparingTo("4500000.00");
        // B: 매도 13,000,000 - 취득(200*1300*100)=26,000,000 → 손실이라 양도소득 -13,000,000
        assertThat(snapshotColumn(accountB, "original_gain_amount"))
                .isEqualByComparingTo("-13000000.00");
    }

    @Test
    @DisplayName("계좌 수가 페이지 크기(200)를 넘어도 전부 처리한다")
    void job_페이지경계를_넘는_계좌도_전부_처리한다() throws Exception {
        int accountCount = 205;
        for (int i = 0; i < accountCount; i++) {
            insertOpenedAccount(("page-%03d".formatted(i)).repeat(3));
        }

        JobExecution execution = jobLauncher.run(taxSnapshotJob, jobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countSnapshots()).isEqualTo(accountCount);
    }

    @Test
    @DisplayName("한 계좌 계산이 실패해도 그 건만 건너뛰고 나머지는 정상 처리한다")
    void job_한계좌_계산실패해도_나머지는_스냅샷을_남긴다() throws Exception {
        Long healthyAccount = insertOpenedAccount("healthy".repeat(9));
        insertFinalizedLot(healthyAccount, LocalDateTime.of(2026, 3, 10, 10, 0), "24000000", "150");

        // 8월 매도(calculatedAt=8/14 이전이라 조회 대상엔 포함됨)인데, RELIEF_RATE 8~12월 규칙 자체를
        // 지워서 findWeight가 못 찾게 만들어 TaxRuleNotFoundException을 강제로 유도한다.
        Long brokenAccount = insertOpenedAccount("broken".repeat(9));
        insertFinalizedLot(brokenAccount, LocalDateTime.of(2026, 8, 10, 10, 0), "10000000", "100");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DELETE FROM tax_rule WHERE rule_type = 'RELIEF_RATE' AND valid_from = '2026-08-01'");
        }

        JobExecution execution = jobLauncher.run(taxSnapshotJob, jobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countSnapshots()).isEqualTo(1);
        assertThat(snapshotColumn(healthyAccount, "original_gain_amount"))
                .isEqualByComparingTo("4500000.00");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM tax_snapshot WHERE account_id = "
                                        + brokenAccount)) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        assertThat(execution.getStepExecutions())
                .singleElement()
                .satisfies(step -> assertThat(step.getSkipCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("같은 계좌를 다시 실행해도 스냅샷은 계좌당 한 행으로 유지된다")
    void job_두번_실행해도_계좌당_한행만_남는다() throws Exception {
        Long accountA = insertOpenedAccount("rerun".repeat(12));
        insertFinalizedLot(accountA, LocalDateTime.of(2026, 3, 10, 10, 0), "24000000", "150");

        jobLauncher.run(taxSnapshotJob, jobParameters());
        JobExecution second = jobLauncher.run(taxSnapshotJob, jobParameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countSnapshots()).isEqualTo(1);
    }

    @Test
    @DisplayName("외부 순매수가 실제로 조회돼 조정비율에 반영되고, 컷오프 이후 판정건은 제외된다")
    void job_외부순매수가_배치전체흐름에서_스냅샷에_반영된다() throws Exception {
        String ciHash = "extflow".repeat(9);
        Long accountId = insertOpenedAccount(ciHash);
        // 매도 20,000,000 - 취득(50*1300*100)=6,500,000 → 양도소득 13,500,000, 1~5월 가중치 100%
        insertFinalizedLot(accountId, LocalDateTime.of(2026, 3, 10, 10, 0), "20000000", "50");

        // 컷오프(calculatedAt=2026-08-14 02:00) 이전 판정 → 반영돼야 함. 6~7월 가중치 80%.
        insertExternalBuy(
                ciHash,
                LocalDateTime.of(2026, 6, 15, 0, 0),
                "5000000",
                LocalDateTime.of(2026, 8, 13, 0, 0));
        // 컷오프 이후 판정 → 반영되면 안 됨. judged_at 조건이 깨지면 이 큰 금액이 섞여
        // adjustRatio가 크게 달라지므로 회귀를 바로 잡아낸다.
        insertExternalBuy(
                ciHash,
                LocalDateTime.of(2026, 6, 20, 0, 0),
                "100000000",
                LocalDateTime.of(2026, 8, 15, 0, 0));

        JobExecution execution = jobLauncher.run(taxSnapshotJob, jobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // weighted_external = 5,000,000 * 0.8 = 4,000,000 (컷오프 이후 건 제외)
        assertThat(snapshotColumn(accountId, "weighted_external_amount"))
                .isEqualByComparingTo("4000000.00");
        // adjustRatio = 1 - (4,000,000 / 20,000,000) = 0.8000
        assertThat(snapshotColumn(accountId, "adjust_ratio")).isEqualByComparingTo("0.8000");
    }

    @Test
    @DisplayName("배치 실행 결과로 계좌의 benefit이 실제로 갱신된다")
    void job_benefit이_외부순매수_여부에_따라_갱신된다() throws Exception {
        String ciHashWithExternal = "benefitreduced".repeat(4) + "aaaa";
        Long accountReduced = insertOpenedAccount(ciHashWithExternal);
        insertFinalizedLot(accountReduced, LocalDateTime.of(2026, 3, 10, 10, 0), "20000000", "50");
        insertExternalBuy(
                ciHashWithExternal,
                LocalDateTime.of(2026, 6, 15, 0, 0),
                "5000000",
                LocalDateTime.of(2026, 8, 13, 0, 0));

        String ciHashNoExternal = "benefitpossible".repeat(4);
        Long accountPossible = insertOpenedAccount(ciHashNoExternal);
        insertFinalizedLot(accountPossible, LocalDateTime.of(2026, 3, 10, 10, 0), "20000000", "50");

        JobExecution execution = jobLauncher.run(taxSnapshotJob, jobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(accountBenefit(accountReduced)).isEqualTo("REDUCED");
        assertThat(accountBenefit(accountPossible)).isEqualTo("POSSIBLE");
    }

    private String accountBenefit(Long accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT benefit FROM account WHERE account_id = " + accountId)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("benefit 변경 저장이 실패하면 같은 청크의 스냅샷 저장도 함께 롤백된다")
    void job_benefit변경_실패시_같은청크의_스냅샷도_롤백된다() throws Exception {
        String ciHash = "rollback".repeat(8);
        Long accountId = insertOpenedAccount(ciHash);
        insertFinalizedLot(accountId, LocalDateTime.of(2026, 3, 10, 10, 0), "20000000", "50");
        // POSSIBLE(초기값)과 다른 상태로 바뀌어야 changeBenefit이 실제로 UPDATE+로그 기록을 시도한다.
        insertExternalBuy(
                ciHash,
                LocalDateTime.of(2026, 6, 15, 0, 0),
                "5000000",
                LocalDateTime.of(2026, 8, 13, 0, 0));

        // account_benefit_log를 없애서 changeBenefit 내부 이력 기록이 실제로 실패하게 만든다.
        // 다른 테스트에 영향을 주지 않도록 검증 후 반드시 테이블을 복구한다.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE account_benefit_log");
        }

        try {
            JobExecution execution = jobLauncher.run(taxSnapshotJob, jobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(countSnapshots()).isZero();
            assertThat(accountBenefit(accountId)).isEqualTo("POSSIBLE");
        } finally {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        """
                        CREATE TABLE account_benefit_log (
                            benefit_id  BIGINT PRIMARY KEY AUTO_INCREMENT,
                            account_id  BIGINT       NOT NULL,
                            prev_status VARCHAR(12),
                            new_status  VARCHAR(12)  NOT NULL,
                            changed_at  DATETIME     NOT NULL,
                            reason      VARCHAR(200)
                        )
                        """);
            }
        }
    }
}
