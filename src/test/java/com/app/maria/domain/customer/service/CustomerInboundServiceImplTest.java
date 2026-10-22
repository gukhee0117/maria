package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.entity.AccountEntity;
import com.app.maria.domain.account.repository.AccountRepository;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.customer.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundSubmitResponseDTO;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.foreignproduct.repository.ForeignProductRepository;
import com.app.maria.domain.inbound.repository.InboundDetailRepository;
import com.app.maria.domain.inbound.repository.InboundMinRepository;
import com.app.maria.domain.inbound.repository.InboundRepository;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.domain.registrablestock.type.GeneralAccountType;
import com.app.maria.global.client.registrablestock.RegistrableStockClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CustomerInboundServiceImplTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long FOREIGN_PRODUCT_ID = 100L;
    private static final Long SOURCE_ACCOUNT_ID = 200L;
    private static final Long STOCK_ID = 300L;
    private static final String CI_HASH = "ci-hash-test";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 9, 0);

    @Mock private AccountRepository accountRepository;
    @Mock private ForeignProductRepository foreignProductRepository;
    @Mock private InboundRepository inboundRepository;
    @Mock private InboundDetailRepository inboundDetailRepository;
    @Mock private InboundMinRepository inboundMinRepository;
    @Mock private RegistrableStockClient registrableStockClient;
    @Mock private BusinessClockService businessClockService;

    @InjectMocks private CustomerInboundServiceImpl service;

    private AccountEntity mockAccount;
    private ForeignProductEntity mockProduct;

    @BeforeEach
    void setUp() {
        mockAccount = mock(AccountEntity.class);
        mockProduct = mock(ForeignProductEntity.class);
    }

    private void stubAccount(Status status) {
        when(accountRepository.findByCustomer_CustomerIdForUpdate(CUSTOMER_ID))
                .thenReturn(Optional.of(mockAccount));
        when(mockAccount.getStatus()).thenReturn(status);
    }

    private void stubCustomer() {
        CustomerEntity mockCustomer = mock(CustomerEntity.class);
        when(mockAccount.getCustomer()).thenReturn(mockCustomer);
        when(mockCustomer.getCiHash()).thenReturn(CI_HASH);
    }

    private void stubEligibleAccount() {
        stubAccount(Status.OPENED);
        stubCustomer();
        when(mockAccount.getAccountId()).thenReturn(ACCOUNT_ID);
        when(foreignProductRepository.findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
                        List.of(FOREIGN_PRODUCT_ID)))
                .thenReturn(List.of(mockProduct));
    }

    private void stubResponseAndPersistence() {
        when(mockProduct.getForeignProductId()).thenReturn(FOREIGN_PRODUCT_ID);
        when(mockProduct.getTicker()).thenReturn("AAPL");
        when(mockProduct.getName()).thenReturn("Apple Inc.");
        when(businessClockService.now()).thenReturn(NOW);
        when(inboundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inboundDetailRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubApprovedQty(BigDecimal approvedQty) {
        when(inboundDetailRepository.sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
                        ACCOUNT_ID, FOREIGN_PRODUCT_ID, SOURCE_ACCOUNT_ID))
                .thenReturn(approvedQty);
    }

    private void stubLotApprovedQty(Long stockId, BigDecimal approvedQty) {
        when(inboundDetailRepository.sumApprovedQtyByAccountAndRegistrableStockId(
                        ACCOUNT_ID, stockId))
                .thenReturn(approvedQty);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private CustomerInboundSubmitRequestDTO request(BigDecimal qty) {
        CustomerInboundSubmitRequestDTO dto = new CustomerInboundSubmitRequestDTO();
        ReflectionTestUtils.setField(dto, "foreignProductId", FOREIGN_PRODUCT_ID);
        ReflectionTestUtils.setField(dto, "sourceGeneralAccountId", SOURCE_ACCOUNT_ID);
        ReflectionTestUtils.setField(dto, "requestedQty", qty);
        return dto;
    }

    private RegistrableStockResponseDTO lot(Long stockId, BigDecimal heldQty) {
        return RegistrableStockResponseDTO.builder()
                .registrableStockId(stockId)
                .foreignProductId(FOREIGN_PRODUCT_ID)
                .generalAccountId(SOURCE_ACCOUNT_ID)
                .accountNo("0000000001")
                .accountType(GeneralAccountType.BROKERAGE)
                .heldQty(heldQty)
                .sourceBroker("리턴증권")
                .purchaseDate(NOW.minusDays(10))
                .purchasePrice(new BigDecimal("150.00"))
                .purchaseCurrency("USD")
                .purchaseFxRate(new BigDecimal("1350.00"))
                .build();
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("요청 수량이 미사용 적격 수량 이내이면 요청량 전량을 승인한다")
    void submit_approvesFullRequestedQty_whenWithinAvailable() {
        stubEligibleAccount();
        stubResponseAndPersistence();
        stubApprovedQty(BigDecimal.ZERO);
        stubLotApprovedQty(STOCK_ID, BigDecimal.ZERO);
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(STOCK_ID, new BigDecimal("100"))));

        CustomerInboundSubmitResponseDTO result =
                service.submit(CUSTOMER_ID, request(new BigDecimal("30")));

        assertThat(result.getApprovedQty()).isEqualByComparingTo("30");
        assertThat(result.getRequestedQty()).isEqualByComparingTo("30");
        assertThat(result.getPartialReason()).isNull();
        assertThat(result.getDetails()).hasSize(1);
        assertThat(result.getProcessedAt()).isEqualTo(NOW);
        verify(inboundRepository).save(any());
        verify(inboundDetailRepository, times(1)).save(any());
        verify(inboundMinRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("요청 수량이 미사용 적격 수량을 초과하면 미사용 수량만 승인하고 BELOW_REQUEST를 반환한다")
    void submit_approvesPartialQty_andReturnsBelowRequestReason() {
        stubEligibleAccount();
        stubResponseAndPersistence();
        stubLotApprovedQty(STOCK_ID, new BigDecimal("30"));
        // snapshotQty=50, alreadyApproved=30 → available=20, requested=100 → approved=20
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(STOCK_ID, new BigDecimal("50"))));
        when(inboundDetailRepository.sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
                        ACCOUNT_ID, FOREIGN_PRODUCT_ID, SOURCE_ACCOUNT_ID))
                .thenReturn(new BigDecimal("30"));

        CustomerInboundSubmitResponseDTO result =
                service.submit(CUSTOMER_ID, request(new BigDecimal("100")));

        assertThat(result.getApprovedQty()).isEqualByComparingTo("20");
        assertThat(result.getRequestedQty()).isEqualByComparingTo("100");
        assertThat(result.getPartialReason()).isEqualTo("BELOW_REQUEST");
    }

    @Test
    @DisplayName("여러 lot에 걸쳐 FIFO 배분하고 lot별 상세와 근거를 각각 저장한다")
    void submit_distributesAcrossMultipleLots_usingFIFO() {
        stubEligibleAccount();
        stubResponseAndPersistence();
        stubApprovedQty(BigDecimal.ZERO);
        Long stock1 = 301L, stock2 = 302L;
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(
                        List.of(
                                lot(stock1, new BigDecimal("10")),
                                lot(stock2, new BigDecimal("20"))));
        stubLotApprovedQty(stock1, BigDecimal.ZERO);
        stubLotApprovedQty(stock2, BigDecimal.ZERO);

        CustomerInboundSubmitResponseDTO result =
                service.submit(CUSTOMER_ID, request(new BigDecimal("25")));

        assertThat(result.getApprovedQty()).isEqualByComparingTo("25");
        assertThat(result.getDetails()).hasSize(2);
        verify(inboundDetailRepository, times(2)).save(any());
        verify(inboundMinRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("계좌가 없으면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void submit_throws_whenAccountNotFound() {
        when(accountRepository.findByCustomer_CustomerIdForUpdate(CUSTOMER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(CUSTOMER_ID, request(BigDecimal.TEN)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));

        verify(inboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("계좌 상태가 거래 불가이면 ACCOUNT_STATE_NOT_ALLOWED 예외를 던진다")
    void submit_throws_whenAccountNotTradeable() {
        stubAccount(Status.CLOSED);

        assertThatThrownBy(() -> service.submit(CUSTOMER_ID, request(BigDecimal.TEN)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_STATE_NOT_ALLOWED));

        verify(inboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("선택 출처·종목에 매칭 lot이 없으면 CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND 예외를 던진다")
    void submit_throws_whenNoMatchingLots() {
        stubAccount(Status.OPENED);
        stubCustomer();
        when(registrableStockClient.findCustomerLots(CI_HASH)).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(CUSTOMER_ID, request(BigDecimal.TEN)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND));

        verify(inboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("NULL 원본 이력이 존재하면 임의 배분을 차단하고 INBOUND_LOT_ORIGIN_UNRESOLVABLE 예외를 던진다")
    void submit_throws_whenNullOriginLegacyExists() {
        stubEligibleAccount();
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(STOCK_ID, new BigDecimal("100"))));
        when(inboundDetailRepository.existsNullOriginByAccountAndProductAndSourceGeneralAccount(
                        ACCOUNT_ID, FOREIGN_PRODUCT_ID, SOURCE_ACCOUNT_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submit(CUSTOMER_ID, request(BigDecimal.TEN)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.INBOUND_LOT_ORIGIN_UNRESOLVABLE));

        verify(inboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("미사용 적격 수량이 0이면 입고 기록을 생성하지 않고 CUSTOMER_INBOUND_QTY_ZERO 예외를 던진다")
    void submit_throws_whenAvailableQtyIsZero() {
        stubEligibleAccount();
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(STOCK_ID, new BigDecimal("50"))));
        when(inboundDetailRepository.sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
                        ACCOUNT_ID, FOREIGN_PRODUCT_ID, SOURCE_ACCOUNT_ID))
                .thenReturn(new BigDecimal("50")); // alreadyApproved = snapshotQty → available = 0

        assertThatThrownBy(() -> service.submit(CUSTOMER_ID, request(BigDecimal.TEN)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_INBOUND_QTY_ZERO));

        verify(inboundRepository, never()).save(any());
    }

    @Test
    @DisplayName("lot별 잔여 수량이 소진되어 배분을 완료할 수 없으면 INBOUND_ALLOCATION_INTEGRITY_FAILED 예외를 던진다")
    void submit_throws_whenLotQtyAlreadyDepleted_causesAllocationFailure() {
        stubEligibleAccount();
        stubApprovedQty(BigDecimal.ZERO);
        when(businessClockService.now()).thenReturn(NOW);
        when(inboundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // account-level: snapshotQty=10, alreadyApproved=0 → approvedQty=10
        // lot-level: sumApproved=10 → lotAvailable=0 → remaining 소진 불가
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(STOCK_ID, new BigDecimal("10"))));
        when(inboundDetailRepository.sumApprovedQtyByAccountAndRegistrableStockId(
                        ACCOUNT_ID, STOCK_ID))
                .thenReturn(new BigDecimal("10"));

        assertThatThrownBy(() -> service.submit(CUSTOMER_ID, request(new BigDecimal("10"))))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.INBOUND_ALLOCATION_INTEGRITY_FAILED));
    }

    @Test
    @DisplayName("동시 요청 직렬화를 위해 쓰기 경로에서 비관적 잠금 조회를 사용한다")
    void submit_usesLockingQuery_forConcurrentRequestSerialization() {
        stubEligibleAccount();
        stubResponseAndPersistence();
        stubApprovedQty(BigDecimal.ZERO);
        stubLotApprovedQty(STOCK_ID, BigDecimal.ZERO);
        when(registrableStockClient.findCustomerLots(CI_HASH))
                .thenReturn(List.of(lot(STOCK_ID, new BigDecimal("100"))));

        service.submit(CUSTOMER_ID, request(BigDecimal.TEN));

        verify(accountRepository).findByCustomer_CustomerIdForUpdate(CUSTOMER_ID);
        verify(accountRepository, never()).findByCustomer_CustomerId(any());
    }
}
