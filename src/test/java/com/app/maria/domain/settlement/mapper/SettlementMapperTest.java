package com.app.maria.domain.settlement.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.domain.settlement.type.SettlementFailureCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.domain.settlement.type.SettlementStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

class SettlementMapperTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 3, 0, 0);

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private KrwExchangeMapper krwExchangeMapper;
    private SettlementBatchMapper settlementBatchMapper;
    private SettlementBatchGuardMapper settlementBatchGuardMapper;
    private SettlementItemMapper settlementItemMapper;
    private SettlementJoinMapper settlementJoinMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-settlement-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource =
                (PooledDataSource)
                        sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        resetSchema();
        sqlSession = sqlSessionFactory.openSession(false);
        krwExchangeMapper = sqlSession.getMapper(KrwExchangeMapper.class);
        settlementBatchMapper = sqlSession.getMapper(SettlementBatchMapper.class);
        settlementBatchGuardMapper = sqlSession.getMapper(SettlementBatchGuardMapper.class);
        settlementItemMapper = sqlSession.getMapper(SettlementItemMapper.class);
        settlementJoinMapper = sqlSession.getMapper(SettlementJoinMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (sqlSession != null) {
            sqlSession.rollback();
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
    @DisplayName("Batch 목록과 단건을 조회하고 같은 날짜의 RUNNING Batch를 집계한다")
    void selectsBatchesAndCountsRunningBatchForDate() {
        SettlementBatchDTO currentRunning =
                insertBatch("settlement-20260803-list-001", CUTOFF.plusHours(9));
        SettlementBatchDTO previousRunning =
                insertBatch("settlement-20260802-list-001", CUTOFF.minusDays(1).plusHours(9));
        SettlementBatchDTO currentCompleted =
                insertBatch("settlement-20260803-list-002", CUTOFF.plusHours(10));
        currentCompleted.setStatus(BatchStatus.COMPLETED);
        assertThat(settlementBatchMapper.updateBatchStatus(currentCompleted)).isOne();

        assertThat(settlementBatchMapper.selectBatches())
                .extracting(SettlementBatchDTO::getBatchId)
                .containsExactly(
                        currentCompleted.getBatchId(),
                        previousRunning.getBatchId(),
                        currentRunning.getBatchId());
        assertThat(settlementBatchMapper.selectBatchById(currentRunning.getBatchId()))
                .contains(currentRunning);
        assertThat(settlementBatchMapper.selectBatchById(999L)).isEmpty();

        SettlementBatchDTO dateCondition =
                SettlementBatchDTO.builder().executedAt(CUTOFF.plusHours(12)).build();
        assertThat(settlementBatchMapper.countRunningBatch(dateCondition)).isOne();

        LocalDate businessDate = CUTOFF.toLocalDate();
        settlementBatchGuardMapper.ensureGuard(businessDate);
        settlementBatchGuardMapper.ensureGuard(businessDate);
        assertThat(settlementBatchGuardMapper.selectGuardForUpdate(businessDate))
                .contains(businessDate);
        assertThat(settlementBatchMapper.selectRunningBatchByBusinessDate(businessDate))
                .contains(currentRunning);
        assertThat(settlementBatchMapper.selectRunningBatchByBusinessDate(businessDate.plusDays(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("확정산 Batch와 대상 Item Snapshot을 생성하고 DTO cursor로 조인 대상을 조회한다")
    void createsBatchSnapshotAndSelectsTarget() {
        SettlementBatchDTO batch = insertRunningBatch("settlement-20260803-001");

        assertThat(settlementItemMapper.insertItemsForTargets(batch)).isOne();

        List<SettlementItemDTO> items =
                settlementItemMapper.selectPendingItems(pendingCursor(batch.getBatchId(), 0L));
        assertThat(items).hasSize(1);

        SettlementJoinDTO targetQuery =
                SettlementJoinDTO.builder()
                        .batchId(batch.getBatchId())
                        .itemId(items.get(0).getItemId())
                        .build();
        SettlementJoinDTO target =
                settlementJoinMapper.selectTargetByItemId(targetQuery).orElseThrow();

        assertThat(target.getExchangeId()).isEqualTo(1L);
        assertThat(target.getAccountId()).isEqualTo(1L);
        assertThat(target.getOrderId()).isEqualTo(1L);
        assertThat(target.getProvisionalAmount()).isEqualByComparingTo("2700000.00");
        assertThat(target.getProvisionalAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 15, 30));
        assertThat(target.getFinalAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0));
        assertThat(target.getSettlementStatus()).isEqualTo(SettlementStatus.PROVISIONAL);
        assertThat(target.getSettlementFxRate()).isEqualByComparingTo("1350.0000");
        assertThat(target.getSellOrderStatus()).isEqualTo(SellOrderStatus.EXECUTED);
        assertThat(target.getPurchaseCurrency()).isEqualTo("USD");
        assertThat(settlementJoinMapper.selectItemDetail(targetQuery)).contains(target);
        SettlementJoinDTO mismatchedQuery =
                SettlementJoinDTO.builder()
                        .batchId(batch.getBatchId() + 1L)
                        .itemId(items.get(0).getItemId())
                        .build();
        assertThat(settlementJoinMapper.selectItemDetail(mismatchedQuery)).isEmpty();
        assertThat(
                        settlementItemMapper.selectPendingItems(
                                pendingCursor(batch.getBatchId(), items.get(0).getItemId())))
                .isEmpty();

        assertThat(settlementItemMapper.insertItemsForTargets(batch)).isZero();
    }

    @Test
    @DisplayName("확정산 성공 시 환전·계좌·건별 잔액·Item 결과를 갱신한다")
    void finalizesExchangeAndUpdatesBalances() throws SQLException {
        SettlementBatchDTO batch = insertRunningBatch("settlement-20260803-002");
        settlementItemMapper.insertItemsForTargets(batch);
        SettlementItemDTO item =
                settlementItemMapper
                        .selectPendingItems(pendingCursor(batch.getBatchId(), 0L))
                        .get(0);

        KrwExchangeDTO exchange = krwExchangeMapper.selectExchangeById(1L).orElseThrow();
        assertThat(krwExchangeMapper.selectExchangeById(999L)).isEmpty();
        assertThat(krwExchangeMapper.selectExchangeByIdForUpdate(1L)).contains(exchange);
        assertThat(exchange.getSettlementStatus()).isEqualTo(SettlementStatus.PROVISIONAL);
        assertThat(krwExchangeMapper.selectAccountAmountForUpdate(1L).orElseThrow())
                .isEqualByComparingTo(new BigDecimal("2700000.00"));

        LocalDateTime finalizedAt = LocalDateTime.of(2026, 8, 3, 9, 0);
        BigDecimal finalRate = new BigDecimal("1400.000000");
        BigDecimal finalAmount = new BigDecimal("2800000.00");
        exchange.setFinalRate(finalRate);
        exchange.setFinalAmount(finalAmount);

        assertThat(krwExchangeMapper.finalizeExchange(exchange)).isOne();
        SettlementJoinDTO targetQuery =
                SettlementJoinDTO.builder()
                        .batchId(batch.getBatchId())
                        .itemId(item.getItemId())
                        .build();
        SettlementJoinDTO finalizedTarget =
                settlementJoinMapper.selectTargetByItemId(targetQuery).orElseThrow();
        assertThat(finalizedTarget.getSettlementStatus()).isEqualTo(SettlementStatus.FINALIZED);
        assertThat(finalizedTarget.getFinalAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0));
        assertThat(krwExchangeMapper.replaceAccountAmount(exchange)).isOne();
        assertThat(krwExchangeMapper.insertLeftAmount(exchange)).isOne();
        item.setResult(SettlementItemResult.SUCCESS);
        item.setProcessedAt(finalizedAt);
        assertThat(settlementItemMapper.updateItemResult(item)).isOne();
        assertThat(settlementJoinMapper.selectTargetByItemId(targetQuery)).isEmpty();
        assertThat(settlementJoinMapper.selectItemDetail(targetQuery))
                .get()
                .extracting(SettlementJoinDTO::getSettlementStatus)
                .isEqualTo(SettlementStatus.FINALIZED);

        assertThat(settlementItemMapper.countPendingItems(batch.getBatchId())).isZero();
        assertThat(settlementItemMapper.countFailedItems(batch.getBatchId())).isZero();
        batch.setStatus(BatchStatus.COMPLETED);
        assertThat(settlementBatchMapper.updateBatchStatus(batch)).isOne();
        assertThat(krwExchangeMapper.finalizeExchange(exchange)).isZero();

        sqlSession.commit();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                """
             SELECT a.amount, la.cur_amount
             FROM account a
             JOIN left_amount la ON la.exchange_id = 1
             WHERE a.account_id = 1
             """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBigDecimal("amount")).isEqualByComparingTo(finalAmount);
            assertThat(resultSet.getBigDecimal("cur_amount")).isEqualByComparingTo(finalAmount);
        }
    }

    @Test
    @DisplayName("실패 Item은 한 번만 FAILED 처리하고 Batch 실패 건수에 반영한다")
    void marksItemFailedOnlyOnce() {
        SettlementBatchDTO batch = insertRunningBatch("settlement-20260803-003");
        settlementItemMapper.insertItemsForTargets(batch);
        SettlementItemDTO item =
                settlementItemMapper
                        .selectPendingItems(pendingCursor(batch.getBatchId(), 0L))
                        .get(0);
        LocalDateTime processedAt = LocalDateTime.of(2026, 8, 3, 9, 5);

        item.setResult(SettlementItemResult.FAILED);
        item.setProcessedAt(processedAt);
        assertThat(settlementItemMapper.updateItemResult(item)).isOne();
        item.setResult(SettlementItemResult.SUCCESS);
        item.setProcessedAt(processedAt.plusMinutes(1));
        assertThat(settlementItemMapper.updateItemResult(item)).isZero();
        SettlementJoinDTO detailQuery =
                SettlementJoinDTO.builder()
                        .batchId(batch.getBatchId())
                        .itemId(item.getItemId())
                        .build();
        assertThat(settlementJoinMapper.selectTargetByItemId(detailQuery)).isEmpty();
        assertThat(settlementJoinMapper.selectItemDetail(detailQuery)).isPresent();
        assertThat(settlementItemMapper.countPendingItems(batch.getBatchId())).isZero();
        assertThat(settlementItemMapper.countFailedItems(batch.getBatchId())).isOne();
        assertThat(settlementBatchMapper.updateBatchStatus(batch)).isZero();
        batch.setStatus(BatchStatus.FAILED);
        assertThat(settlementBatchMapper.updateBatchStatus(batch)).isOne();
    }

    @Test
    @DisplayName("실패 Item 재처리는 새 이력을 만들고 진행 중 또는 성공한 동일 환전의 중복 재처리를 막는다")
    void createsOnlyOneRetryItemForFailedExchange() {
        SettlementBatchDTO batch = insertRunningBatch("settlement-20260803-retry");
        settlementItemMapper.insertItemsForTargets(batch);
        SettlementItemDTO failedItem =
                settlementItemMapper
                        .selectPendingItems(pendingCursor(batch.getBatchId(), 0L))
                        .get(0);
        failedItem.setResult(SettlementItemResult.FAILED);
        failedItem.setProcessedAt(CUTOFF.plusHours(10));
        failedItem.setFailureCode(SettlementFailureCode.EXCHANGE_RATE_NOT_FOUND);
        failedItem.setFailureMessage("환율 없음");
        assertThat(settlementItemMapper.updateItemResult(failedItem)).isOne();
        batch.setStatus(BatchStatus.FAILED);
        batch.setFailureMessage("정산 Item 실패 1건 발생");
        assertThat(settlementBatchMapper.updateBatchStatus(batch)).isOne();

        SettlementItemDTO retry =
                SettlementItemDTO.builder()
                        .batchId(batch.getBatchId())
                        .itemId(failedItem.getItemId())
                        .build();
        assertThat(settlementItemMapper.insertRetryItem(retry)).isOne();
        assertThat(retry.getItemId()).isNotEqualTo(failedItem.getItemId());
        assertThat(settlementItemMapper.selectItemById(retry.getItemId()))
                .hasValueSatisfying(item -> assertThat(item.getResult()).isNull());

        SettlementBatchDTO retryBatch =
                SettlementBatchDTO.builder().batchId(batch.getBatchId()).build();
        assertThat(settlementBatchMapper.markBatchRetryRunning(retryBatch)).isOne();

        SettlementItemDTO duplicate =
                SettlementItemDTO.builder()
                        .batchId(batch.getBatchId())
                        .itemId(failedItem.getItemId())
                        .build();
        assertThat(settlementItemMapper.insertRetryItem(duplicate)).isZero();

        retry.setResult(SettlementItemResult.SUCCESS);
        retry.setProcessedAt(CUTOFF.plusHours(11));
        assertThat(settlementItemMapper.updateItemResult(retry)).isOne();
        assertThat(settlementBatchMapper.refreshBatchStatusAfterRetry(batch.getBatchId())).isOne();
        assertThat(settlementBatchMapper.selectBatchById(batch.getBatchId()))
                .hasValueSatisfying(
                        refreshed -> {
                            assertThat(refreshed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                            assertThat(refreshed.getFailureMessage()).isNull();
                            assertThat(refreshed.getTotalCount()).isOne();
                            assertThat(refreshed.getSuccessCount()).isOne();
                            assertThat(refreshed.getFailedCount()).isZero();
                            assertThat(refreshed.getProcessedCount()).isOne();
                        });
        assertThat(settlementBatchMapper.refreshBatchStatusAfterRetry(batch.getBatchId())).isZero();
        SettlementItemDTO afterSuccess =
                SettlementItemDTO.builder()
                        .batchId(batch.getBatchId())
                        .itemId(failedItem.getItemId())
                        .build();
        assertThat(settlementItemMapper.insertRetryItem(afterSuccess)).isZero();
    }

    @Test
    @DisplayName("동일 업무일 Guard를 동시에 잠그면 두 번째 실행은 기존 RUNNING Batch를 확인한다")
    void concurrentBusinessDateGuardPreventsSecondRunningBatch() throws Exception {
        LocalDate businessDate = CUTOFF.toLocalDate();
        settlementBatchGuardMapper.ensureGuard(businessDate);
        sqlSession.commit();

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first =
                    executor.submit(
                            () -> {
                                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                                    SettlementBatchGuardMapper guardMapper =
                                            session.getMapper(SettlementBatchGuardMapper.class);
                                    SettlementBatchMapper batchMapper =
                                            session.getMapper(SettlementBatchMapper.class);
                                    guardMapper.selectGuardForUpdate(businessDate).orElseThrow();
                                    SettlementBatchDTO batch =
                                            SettlementBatchDTO.builder()
                                                    .executedAt(CUTOFF.plusHours(9))
                                                    .status(BatchStatus.RUNNING)
                                                    .runId("concurrent-first")
                                                    .build();
                                    batchMapper.insertBatch(batch);
                                    firstLocked.countDown();
                                    releaseFirst.await(3, TimeUnit.SECONDS);
                                    session.commit();
                                }
                                return null;
                            });

            Future<Boolean> second =
                    executor.submit(
                            () -> {
                                firstLocked.await(3, TimeUnit.SECONDS);
                                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                                    SettlementBatchGuardMapper guardMapper =
                                            session.getMapper(SettlementBatchGuardMapper.class);
                                    SettlementBatchMapper batchMapper =
                                            session.getMapper(SettlementBatchMapper.class);
                                    guardMapper.selectGuardForUpdate(businessDate).orElseThrow();
                                    return batchMapper
                                            .selectRunningBatchByBusinessDate(businessDate)
                                            .isPresent();
                                }
                            });

            assertThat(firstLocked.await(3, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            first.get(3, TimeUnit.SECONDS);
            assertThat(second.get(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private SettlementBatchDTO insertRunningBatch(String runId) {
        return insertBatch(runId, CUTOFF.plusHours(9));
    }

    private SettlementBatchDTO insertBatch(String runId, LocalDateTime executedAt) {
        SettlementBatchDTO batch =
                SettlementBatchDTO.builder()
                        .executedAt(executedAt)
                        .status(BatchStatus.RUNNING)
                        .runId(runId)
                        .build();

        assertThat(settlementBatchMapper.insertBatch(batch)).isOne();
        assertThat(batch.getBatchId()).isPositive();
        assertThat(settlementBatchMapper.selectBatchByRunId(runId)).contains(batch);
        return batch;
    }

    private SettlementItemDTO pendingCursor(Long batchId, Long lastItemId) {
        return SettlementItemDTO.builder().batchId(batchId).itemId(lastItemId).build();
    }

    @Test
    @DisplayName("가환전 대기금액은 PROVISIONAL 상태 건만 합산한다")
    void sumsProvisionalAmountByStatus() {
        BigDecimal pending =
                krwExchangeMapper.sumProvisionalAmountByStatus(SettlementStatus.PROVISIONAL);

        assertThat(pending).isEqualByComparingTo("2900000.00");
    }

    @Test
    @DisplayName("확정산 완료금액은 final_at이 구간 안에 있는 FINALIZED 건만 합산한다")
    void sumsFinalizedAmountBetweenDateRange() {
        BigDecimal finalizedOnDay =
                krwExchangeMapper.sumFinalizedAmountBetween(
                        LocalDateTime.of(2026, 8, 3, 0, 0), LocalDateTime.of(2026, 8, 4, 0, 0));
        BigDecimal finalizedOutsideDay =
                krwExchangeMapper.sumFinalizedAmountBetween(
                        LocalDateTime.of(2026, 8, 2, 0, 0), LocalDateTime.of(2026, 8, 3, 0, 0));

        assertThat(finalizedOnDay).isEqualByComparingTo("110000.00");
        assertThat(finalizedOutsideDay).isEqualByComparingTo("0");
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
          CREATE TABLE account (
              account_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              account_no VARCHAR(10),
              status VARCHAR(20) NOT NULL,
              amount DECIMAL(15, 0) NOT NULL DEFAULT 0
          )
          """);
            statement.execute(
                    """
          CREATE TABLE inbound_detail (
              inbound_detail_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              purchase_currency VARCHAR(10) NOT NULL
          )
          """);
            statement.execute(
                    """
          CREATE TABLE sell_order (
              order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              inbound_detail_id BIGINT NOT NULL,
              foreign_product_id BIGINT NOT NULL,
              settlement_fx_rate DECIMAL(15, 4),
              status VARCHAR(20) NOT NULL
          )
          """);
            statement.execute(
                    """
          CREATE TABLE foreign_product (
              foreign_product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              ticker VARCHAR(20) NOT NULL,
              name VARCHAR(100) NOT NULL
          )
          """);
            statement.execute(
                    """
          CREATE TABLE krw_exchange (
              exchange_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              account_id BIGINT NOT NULL,
              order_id BIGINT NOT NULL UNIQUE,
              provisional_amount DECIMAL(15, 2) NOT NULL,
              provisional_at DATETIME NOT NULL,
              final_rate DECIMAL(15, 6),
              final_amount DECIMAL(15, 0),
              final_at DATETIME,
              settlement_status VARCHAR(20) NOT NULL
          )
          """);
            statement.execute(
                    """
          CREATE TABLE settlement_batch (
              batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              executed_at DATETIME NOT NULL,
              status VARCHAR(20) NOT NULL,
              run_id VARCHAR(50) NOT NULL UNIQUE,
              failure_message VARCHAR(500)
          )
          """);
            statement.execute(
                    """
          CREATE TABLE settlement_batch_guard (
              business_date DATE PRIMARY KEY,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
          )
          """);
            statement.execute(
                    """
          CREATE TABLE settlement_item (
              item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              batch_id BIGINT NOT NULL,
              exchange_id BIGINT NOT NULL,
              result VARCHAR(10),
              processed_at DATETIME,
              failure_code VARCHAR(50),
              failure_message VARCHAR(500)
          )
          """);
            statement.execute(
                    """
          CREATE TABLE left_amount (
              left_amount_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              exchange_id BIGINT NOT NULL UNIQUE,
              cur_amount DECIMAL(15, 0) NOT NULL
          )
          """);

            statement.execute(
                    """
          INSERT INTO account (account_id, account_no, status, amount)
          VALUES (1, '1234567890', 'OPENED', 2700000.00)
          """);
            statement.execute(
                    """
          INSERT INTO inbound_detail (inbound_detail_id, purchase_currency)
          VALUES (1, 'USD'), (2, 'USD')
          """);
            statement.execute(
                    """
          INSERT INTO sell_order (
              order_id, inbound_detail_id, foreign_product_id, settlement_fx_rate, status
          ) VALUES
              (1, 1, 1, 1350.0000, 'EXECUTED'),
              (2, 1, 1, NULL, 'RECEIVED'),
              (3, 2, 1, 1300.0000, 'EXECUTED'),
              (4, 2, 1, 1300.0000, 'EXECUTED')
          """);
            statement.execute(
                    """
          INSERT INTO foreign_product (foreign_product_id, ticker, name)
          VALUES (1, 'AAPL', 'Apple Inc.')
          """);
            statement.execute(
                    """
          INSERT INTO krw_exchange (
              exchange_id,
              account_id,
              order_id,
              provisional_amount,
              provisional_at,
              final_rate,
              final_amount,
              final_at,
              settlement_status
          ) VALUES
              (1, 1, 1, 2700000.00, '2026-08-02 15:30:00', NULL, NULL,
               '2026-08-03 00:00:00', 'PROVISIONAL'),
              (2, 1, 2, 100000.00, '2026-08-02 15:35:00', NULL, NULL,
               '2026-08-03 00:00:00', 'PROVISIONAL'),
              (3, 1, 3, 100000.00, '2026-08-03 00:00:00', NULL, NULL,
               '2026-08-04 00:00:00', 'PROVISIONAL'),
              (4, 1, 4, 100000.00, '2026-08-02 15:40:00', 1400.000000, 110000.00,
               '2026-08-03 09:00:00', 'FINALIZED')
          """);
        }
    }
}
