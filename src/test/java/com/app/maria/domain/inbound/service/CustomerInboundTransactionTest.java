package com.app.maria.domain.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.app.maria.domain.inbound.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundCandidateResponseDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundCandidatesResponseDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundSubmitResponseDTO;
import com.app.maria.domain.outbound.dto.request.CustomerOutboundRequestDTO;
import com.app.maria.domain.outbound.dto.response.CustomerOutboundResponseDTO;
import com.app.maria.domain.outbound.service.CustomerOutboundService;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.domain.registrablestock.type.GeneralAccountType;
import com.app.maria.global.client.registrablestock.RegistrableStockClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 실제 서비스 프록시·MyBatis Mapper·H2 트랜잭션을 사용한다. 외부 API와 업무시각만 대체한다. 테스트 자체는 트랜잭션을 열지 않으므로 서비스 호출 종료 후 별도
 * 연결로 커밋·롤백 결과를 확인한다. H2 검증이며 MariaDB의 격리 수준·잠금 동작 검증을 대체하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("inboundtransactiontest")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class CustomerInboundTransactionTest {
    private static final String CI_HASH = "inbound-transaction-test-ci";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 9, 0);

    @Autowired private CustomerInboundService service;
    @Autowired private CustomerOutboundService outboundService;
    @Autowired private CustomerInboundQueryService queryService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private RegistrableStockClient registrableStockClient;
    @MockitoBean private BusinessClockService businessClockService;

    @BeforeAll
    void createSchema() {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS customer (
                    customer_id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL,
                    birth_date DATE NOT NULL, phone VARCHAR(20), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS account (
                    account_id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL UNIQUE REFERENCES customer(customer_id),
                    status VARCHAR(20) NOT NULL, opened_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    account_no VARCHAR(10) UNIQUE, limit_amount DECIMAL(15,0) NOT NULL,
                    amount DECIMAL(15,0) NOT NULL DEFAULT 0, benefit VARCHAR(12),
                    last_domestic_trade_id BIGINT
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS foreign_product (
                    foreign_product_id BIGINT PRIMARY KEY, ticker VARCHAR(20) NOT NULL,
                    name VARCHAR(100) NOT NULL, market VARCHAR(50) NOT NULL,
                    currency VARCHAR(10), type VARCHAR(15) NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS inbound (
                    inbound_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    account_id BIGINT NOT NULL REFERENCES account(account_id),
                    requested_qty DECIMAL(15,4) NOT NULL,
                    current_holding_at_request DECIMAL(15,4),
                    approved_qty DECIMAL(15,4) NOT NULL,
                    processed_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS inbound_detail (
                    inbound_detail_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    inbound_id BIGINT NOT NULL REFERENCES inbound(inbound_id),
                    foreign_product_id BIGINT NOT NULL REFERENCES foreign_product(foreign_product_id),
                    registrable_stock_id BIGINT,
                    source_broker VARCHAR(20),
                    source_general_account_id BIGINT,
                    account_type VARCHAR(30),
                    recorded_at TIMESTAMP NOT NULL,
                    purchase_date TIMESTAMP NOT NULL,
                    purchase_price DECIMAL(15,4) NOT NULL,
                    purchase_currency VARCHAR(10) NOT NULL,
                    purchase_fx_rate DECIMAL(15,4) NOT NULL,
                    qty DECIMAL(15,4) NOT NULL,
                    current_qty DECIMAL(15,4) NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS inbound_min (
                    inbound_min_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    inbound_detail_id BIGINT NOT NULL REFERENCES inbound_detail(inbound_detail_id),
                    requested_qty DECIMAL(15,4) NOT NULL,
                    approved_qty DECIMAL(15,4) NOT NULL,
                    snapshot_qty DECIMAL(15,4) NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS outbound (
                    outbound_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    inbound_detail_id BIGINT NOT NULL REFERENCES inbound_detail(inbound_detail_id),
                    qty DECIMAL(15,4) NOT NULL,
                    reason VARCHAR(50),
                    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @BeforeEach
    void seedCommittedData() {
        jdbc.update("DELETE FROM outbound");
        jdbc.update("DELETE FROM inbound_min");
        jdbc.update("DELETE FROM inbound_detail");
        jdbc.update("DELETE FROM inbound");
        jdbc.update("DELETE FROM foreign_product");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM customer");
        jdbc.update(
                """
                INSERT INTO customer
                    (customer_id, name, birth_date, investor_type, ci_hash, created_at)
                VALUES (1, '트랜잭션테스트', '2000-01-01', 'NEUTRAL', ?, '2026-10-01 09:00:00')
                """,
                CI_HASH);
        jdbc.update(
                """
                INSERT INTO account
                    (account_id, customer_id, status, opened_at, created_at,
                     account_no, limit_amount, amount)
                VALUES (10, 1, 'OPENED', '2026-10-01 09:00:00', '2026-10-01 09:00:00',
                        '0000000010', 50000000, 0)
                """);
        jdbc.update(
                """
                INSERT INTO foreign_product (foreign_product_id, ticker, name, market, currency, type)
                VALUES (100, 'AAPL', 'Apple Inc.', 'NASDAQ', 'USD', 'FOREIGN_STOCK')
                """);
        when(businessClockService.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("정상 입고는 세 테이블에 커밋되고 원본별 FIFO 배분 수량이 보존된다")
    void commitsParentDetailsAndEvidenceWithFifoQuantities() {
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "10", "USD"), lot(301L, "20", "USD")));

        CustomerInboundSubmitResponseDTO result = service.submit(1L, request("25"));

        assertThat(result.getApprovedQty()).isEqualByComparingTo("25");
        assertThat(result.getPartialReason()).isNull();
        assertThat(rowCounts()).containsExactly(1, 2, 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT qty FROM inbound_detail WHERE registrable_stock_id = 300",
                                BigDecimal.class))
                .isEqualByComparingTo("10");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT qty FROM inbound_detail WHERE registrable_stock_id = 301",
                                BigDecimal.class))
                .isEqualByComparingTo("15");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(approved_qty) FROM inbound_min", BigDecimal.class))
                .isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("동일 계좌의 8주 동시 신청 두 건은 잠금 대기 후 8주·2주로 커밋된다")
    void concurrentRequestsCannotApproveMoreThanAvailable() throws Exception {
        runCompetingRequests("8", false);
    }

    @Test
    @DisplayName("동일 계좌의 10주 동시 신청 두 건 중 뒤 요청은 소진된 수량을 재검증해 거절된다")
    void concurrentRequestIsRejectedAfterEarlierRequestConsumesAll() throws Exception {
        runCompetingRequests("10", true);
    }

    @Test
    @DisplayName("일부 상세·근거 저장 후 배분 불일치가 발견되면 새 행만 모두 롤백한다")
    void allocationFailureRollsBackAllNewRowsAndPreservesExistingHistory() {
        // 기존: sourceAccount=201, registrableStockId=300에서 10주 입고
        // 요청: sourceAccount=200, lot300(stockId=300,10주), lot301(stockId=301,10주), qty=15
        // 계좌 레벨: alreadyInbounded(source=200)=0, available=20, approvedQty=15
        // lot 레벨: lot300의 lotAlreadyApproved(registrableStockId=300)=10 → skip
        //           lot301은 10주 → remaining=5 소진 불가 → INTEGRITY_FAILED
        seedInconsistentHistory();
        List<List<Integer>> beforeRollback = new CopyOnWriteArrayList<>();
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenAnswer(
                        invocation -> {
                            observeRowsBeforeCompletion(beforeRollback);
                            return List.of(lot(300L, "10", "USD"), lot(301L, "10", "USD"));
                        });

        assertThatThrownBy(() -> service.submit(1L, request("15")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.INBOUND_ALLOCATION_INTEGRITY_FAILED));

        assertThat(beforeRollback).containsExactly(List.of(2, 2, 2));
        assertThat(rowCounts()).containsExactly(1, 1, 1);
        assertThat(jdbc.queryForObject("SELECT SUM(qty) FROM inbound_detail", BigDecimal.class))
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("출고 이력이 있으면 출고 수량만큼 재입고할 수 있고, 스냅샷 전량을 소진하면 추가 입고가 거절된다")
    void reinboundSucceedsAfterOutboundAndIsBlockedWhenSnapshotExhausted() {
        // Step 1: 5주 입고
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        CustomerInboundSubmitResponseDTO first = service.submit(1L, request("5"));
        assertThat(first.getApprovedQty()).isEqualByComparingTo("5");

        // Step 2: 3주 출고 직접 삽입 (current_qty 차감 + outbound 기록)
        Long detailId =
                jdbc.queryForObject(
                        "SELECT inbound_detail_id FROM inbound_detail"
                                + " WHERE registrable_stock_id = 300",
                        Long.class);
        jdbc.update(
                "UPDATE inbound_detail SET current_qty = current_qty - 3"
                        + " WHERE inbound_detail_id = ?",
                detailId);
        jdbc.update(
                "INSERT INTO outbound (inbound_detail_id, qty, reason, processed_at)"
                        + " VALUES (?, 3, 'WITHDRAWAL_OF_INTENT', '2026-10-22 10:00:00')",
                detailId);

        // Step 3: 3주 재입고 — netLocked = 5-3 = 2, available = 5-2 = 3 → 승인
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        CustomerInboundSubmitResponseDTO reinbound = service.submit(1L, request("3"));
        assertThat(reinbound.getApprovedQty()).isEqualByComparingTo("3");
        assertThat(reinbound.getPartialReason()).isNull();
        assertThat(rowCounts()).containsExactly(2, 2, 2);

        // Step 4: 추가 입고 거절 — netLocked = (5+3)-3 = 5, available = 0
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        assertThatThrownBy(() -> service.submit(1L, request("1")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        ex ->
                                assertThat(ex.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_INBOUND_QTY_ZERO));
    }

    @Test
    @DisplayName("두 번째 상세의 DB 제약 위반은 앞서 저장한 행까지 롤백하고 이후 정상 신청을 허용한다")
    void databaseFailureRollsBackEarlierInsertsAndReleasesAccountLock() {
        List<List<Integer>> beforeRollback = new CopyOnWriteArrayList<>();
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenAnswer(
                        invocation -> {
                            observeRowsBeforeCompletion(beforeRollback);
                            return List.of(
                                    lot(300L, "10", "USD"), lot(301L, "10", "CURRENCY_TOO_LONG"));
                        });

        assertThatThrownBy(() -> service.submit(1L, request("15")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(beforeRollback).containsExactly(List.of(1, 1, 1));
        assertThat(rowCounts()).containsExactly(0, 0, 0);

        doReturn(List.of(lot(300L, "10", "USD"), lot(301L, "10", "USD")))
                .when(registrableStockClient)
                .findCustomerLots(CI_HASH);
        CustomerInboundSubmitResponseDTO recovered = service.submit(1L, request("15"));
        assertThat(recovered.getApprovedQty()).isEqualByComparingTo("15");
        assertThat(rowCounts()).containsExactly(1, 2, 2);
    }

    @Test
    @DisplayName("5주 입고·2주 매도 시뮬레이션·3주 출고·3주 재입고 후 최종 보유량은 3주다")
    void fullFlowInboundSimulateSellOutboundReinbound() {
        // Step 1: 5주 입고
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        CustomerInboundSubmitResponseDTO first = service.submit(1L, request("5"));
        assertThat(first.getApprovedQty()).isEqualByComparingTo("5");

        // Step 2: 2주 매도 시뮬레이션 — sell_order/krw_exchange 없이 current_qty만 차감
        jdbc.update(
                "UPDATE inbound_detail SET current_qty = current_qty - 2"
                        + " WHERE registrable_stock_id = 300");

        // Step 3: 3주 출고 (실제 서비스) — FIFO, current_qty=3에서 3주 전부
        CustomerOutboundResponseDTO outbound = outboundService.submit(1L, outboundRequest("3"));
        assertThat(outbound.getTotalOutboundQty()).isEqualByComparingTo("3");
        assertThat(outbound.getLotCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbound", Integer.class))
                .isEqualTo(1);

        // Step 4: 3주 재입고 — netLocked = 5-3 = 2, available = 5-2 = 3 → 전량 승인
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        CustomerInboundSubmitResponseDTO reinbound = service.submit(1L, request("3"));
        assertThat(reinbound.getApprovedQty()).isEqualByComparingTo("3");
        assertThat(reinbound.getPartialReason()).isNull();

        // 최종: inbound 2건, detail 2건, outbound 1건
        assertThat(rowCounts()).containsExactly(2, 2, 2);
        // 첫 detail current_qty = 0 (매도 2 + 출고 3), 재입고 detail current_qty = 3
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(current_qty) FROM inbound_detail", BigDecimal.class))
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("출고·재입고를 반복해도 스냅샷 기준 가용 수량이 정확히 누적 관리된다")
    void repeatedOutboundAndReinboundCyclesAreTrackedCorrectly() {
        // 1. 5주 입고
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        service.submit(1L, request("5"));

        // 2. 3주 출고 → netLocked = 5-3 = 2, available = 3
        CustomerOutboundResponseDTO out1 = outboundService.submit(1L, outboundRequest("3"));
        assertThat(out1.getTotalOutboundQty()).isEqualByComparingTo("3");

        // 3. 3주 재입고 → netLocked = (5+3)-3 = 5, available = 0
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        CustomerInboundSubmitResponseDTO reinbound1 = service.submit(1L, request("3"));
        assertThat(reinbound1.getApprovedQty()).isEqualByComparingTo("3");

        // 4. 3주 출고 → FIFO: 첫 detail(current_qty=2) 소진 후 재입고 detail에서 1주 추가
        //    lotCount=2로 두 상세에 걸쳐 처리됨을 검증
        CustomerOutboundResponseDTO out2 = outboundService.submit(1L, outboundRequest("3"));
        assertThat(out2.getTotalOutboundQty()).isEqualByComparingTo("3");
        assertThat(out2.getLotCount()).isEqualTo(2);

        // 5. 3주 재입고 → alreadyInbounded=8, alreadyOutbounded=6, netLocked=2, available=3 → 전량 승인
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        CustomerInboundSubmitResponseDTO reinbound2 = service.submit(1L, request("3"));
        assertThat(reinbound2.getApprovedQty()).isEqualByComparingTo("3");

        // 6. 스냅샷(5주) 전량 소진 — netLocked = (5+3+3)-(3+3) = 5 → available=0 → 거절
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(300L, "5", "USD")));
        assertThatThrownBy(() -> service.submit(1L, request("1")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        ex ->
                                assertThat(ex.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_INBOUND_QTY_ZERO));

        // 총 outbound qty = 6 (3+3), outbound 행 3건 (out1×1 + out2×2 상세)
        assertThat(rowCounts()).containsExactly(3, 3, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbound", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(qty) FROM outbound", BigDecimal.class))
                .isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("서로 다른 출처 계좌의 입고 이력이 있으면 후보 조회에서 수량이 독립적으로 계산된다")
    void candidatesShowIndependentAvailabilityPerSourceAccount() {
        // source=200에서 5주 입고 (lot300 사용)
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lotFromAccount(300L, "5", 200L)));
        service.submit(1L, requestFromAccount("5", 200L));

        // 후보 조회 시 source=200(5주 소진)과 source=201(5주 미사용) 두 계좌를 반환
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(
                        List.of(
                                lotFromAccount(300L, "5", 200L),
                                lotFromAccount(400L, "5", 201L)));
        CustomerInboundCandidatesResponseDTO candidates = queryService.getCandidates(1L, 100L);

        // source=200은 available=0으로 필터 제외, source=201만 남음
        assertThat(candidates.getCandidates()).hasSize(1);
        CustomerInboundCandidateResponseDTO candidate = candidates.getCandidates().get(0);
        assertThat(candidate.getSourceGeneralAccountId()).isEqualTo(201L);
        assertThat(candidate.getEstimatedAvailableQty()).isEqualByComparingTo("5");
        assertThat(candidate.getSnapshotQty()).isEqualByComparingTo("5");
        assertThat(candidate.getAlreadyApprovedQty()).isEqualByComparingTo("0");

        // source=201로 실제 입고 — source=200 이력과 무관하게 5주 전량 승인 (계좌 독립성 직접 검증)
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lotFromAccount(400L, "5", 201L)));
        CustomerInboundSubmitResponseDTO inbound201 =
                service.submit(1L, requestFromAccount("5", 201L));
        assertThat(inbound201.getApprovedQty()).isEqualByComparingTo("5");
        assertThat(inbound201.getPartialReason()).isNull();
    }

    private void runCompetingRequests(String requestedQty, boolean secondMustFail)
            throws Exception {
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger externalCalls = new AtomicInteger();
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            if (externalCalls.incrementAndGet() == 1) {
                                firstHasLock.countDown();
                                assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
                            }
                            return List.of(lot(300L, "10", "USD"));
                        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<CustomerInboundSubmitResponseDTO> first =
                    workers.submit(() -> service.submit(1L, request(requestedQty)));
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<CustomerInboundSubmitResponseDTO> second =
                    workers.submit(
                            () -> {
                                secondStarted.countDown();
                                return service.submit(1L, request(requestedQty));
                            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(externalCalls.get()).isEqualTo(1);
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).getApprovedQty())
                    .isEqualByComparingTo(requestedQty);
            if (secondMustFail) {
                assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOfSatisfying(
                                AppException.class,
                                exception ->
                                        assertThat(exception.getErrorType())
                                                .isEqualTo(ErrorType.CUSTOMER_INBOUND_QTY_ZERO));
                assertThat(rowCounts()).containsExactly(1, 1, 1);
            } else {
                CustomerInboundSubmitResponseDTO later = second.get(10, TimeUnit.SECONDS);
                assertThat(later.getApprovedQty()).isEqualByComparingTo("2");
                assertThat(later.getPartialReason()).isEqualTo("BELOW_REQUEST");
                assertThat(rowCounts()).containsExactly(2, 2, 2);
            }
            assertThat(externalCalls.get()).isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(approved_qty) FROM inbound", BigDecimal.class))
                    .isEqualByComparingTo("10");
            assertThat(jdbc.queryForObject("SELECT SUM(qty) FROM inbound_detail", BigDecimal.class))
                    .isEqualByComparingTo("10");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(approved_qty) FROM inbound_min", BigDecimal.class))
                    .isEqualByComparingTo("10");
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void observeRowsBeforeCompletion(List<List<Integer>> observations) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void beforeCompletion() {
                        observations.add(rowCounts());
                    }
                });
    }

    private List<Integer> rowCounts() {
        return List.of(
                jdbc.queryForObject("SELECT COUNT(*) FROM inbound", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM inbound_detail", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM inbound_min", Integer.class));
    }

    private void seedInconsistentHistory() {
        jdbc.update(
                """
                INSERT INTO inbound (inbound_id, account_id, requested_qty, approved_qty, processed_at)
                VALUES (900, 10, 10, 10, '2026-10-20 09:00:00')
                """);
        jdbc.update(
                """
                INSERT INTO inbound_detail
                    (inbound_detail_id, inbound_id, foreign_product_id, registrable_stock_id,
                     source_general_account_id, recorded_at, purchase_date, purchase_price,
                     purchase_currency, purchase_fx_rate, qty, current_qty)
                VALUES (900, 900, 100, 300, 201, '2026-10-20 09:00:00', '2025-11-01 09:00:00',
                        150, 'USD', 1350, 10, 10)
                """);
        jdbc.update(
                """
                INSERT INTO inbound_min (inbound_detail_id, requested_qty, approved_qty, snapshot_qty)
                VALUES (900, 10, 10, 10)
                """);
    }

    private CustomerInboundSubmitRequestDTO request(String qty) {
        CustomerInboundSubmitRequestDTO request = new CustomerInboundSubmitRequestDTO();
        ReflectionTestUtils.setField(request, "foreignProductId", 100L);
        ReflectionTestUtils.setField(request, "sourceGeneralAccountId", 200L);
        ReflectionTestUtils.setField(request, "requestedQty", new BigDecimal(qty));
        return request;
    }

    private RegistrableStockResponseDTO lot(Long stockId, String heldQty, String currency) {
        return RegistrableStockResponseDTO.builder()
                .registrableStockId(stockId)
                .foreignProductId(100L)
                .generalAccountId(200L)
                .accountNo("0000000200")
                .accountType(GeneralAccountType.BROKERAGE)
                .heldQty(new BigDecimal(heldQty))
                .sourceBroker("원출처증권")
                .purchaseDate(LocalDateTime.of(2025, 11, stockId == 300L ? 1 : 2, 9, 0))
                .purchasePrice(new BigDecimal("150.0000"))
                .purchaseCurrency(currency)
                .purchaseFxRate(new BigDecimal("1350.0000"))
                .build();
    }

    private RegistrableStockResponseDTO lotFromAccount(
            Long stockId, String heldQty, Long sourceAccountId) {
        return RegistrableStockResponseDTO.builder()
                .registrableStockId(stockId)
                .foreignProductId(100L)
                .generalAccountId(sourceAccountId)
                .accountNo(String.format("%010d", sourceAccountId))
                .accountType(GeneralAccountType.BROKERAGE)
                .heldQty(new BigDecimal(heldQty))
                .sourceBroker("원출처증권")
                .purchaseDate(LocalDateTime.of(2025, 11, stockId == 300L ? 1 : 2, 9, 0))
                .purchasePrice(new BigDecimal("150.0000"))
                .purchaseCurrency("USD")
                .purchaseFxRate(new BigDecimal("1350.0000"))
                .build();
    }

    private CustomerInboundSubmitRequestDTO requestFromAccount(
            String qty, Long sourceAccountId) {
        CustomerInboundSubmitRequestDTO req = new CustomerInboundSubmitRequestDTO();
        ReflectionTestUtils.setField(req, "foreignProductId", 100L);
        ReflectionTestUtils.setField(req, "sourceGeneralAccountId", sourceAccountId);
        ReflectionTestUtils.setField(req, "requestedQty", new BigDecimal(qty));
        return req;
    }

    private CustomerOutboundRequestDTO outboundRequest(String qty) {
        CustomerOutboundRequestDTO req = new CustomerOutboundRequestDTO();
        ReflectionTestUtils.setField(req, "foreignProductId", 100L);
        ReflectionTestUtils.setField(req, "qty", new BigDecimal(qty));
        return req;
    }
}
