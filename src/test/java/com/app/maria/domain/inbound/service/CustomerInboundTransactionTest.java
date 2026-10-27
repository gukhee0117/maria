package com.app.maria.domain.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.app.maria.domain.inbound.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundSubmitResponseDTO;
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
    }

    @BeforeEach
    void seedCommittedData() {
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
}
