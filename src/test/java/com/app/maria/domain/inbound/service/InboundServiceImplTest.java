package com.app.maria.domain.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.foreignproduct.type.ForeignProductType;
import com.app.maria.domain.inbound.dto.InboundAccountSummaryDTO;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.dto.InboundHoldingDTO;
import com.app.maria.domain.inbound.dto.InboundListDTO;
import com.app.maria.domain.inbound.dto.InboundLotDTO;
import com.app.maria.domain.inbound.dto.InboundPageDTO;
import com.app.maria.domain.inbound.dto.InboundPriorApprovalDTO;
import com.app.maria.domain.inbound.dto.InboundSummaryDTO;
import com.app.maria.domain.inbound.dto.SourceLotApprovedQtyDTO;
import com.app.maria.domain.inbound.dto.request.InboundRequestDTO;
import com.app.maria.domain.inbound.dto.response.AccountHoldingResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundPriorApprovalResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundSummaryResponseDTO;
import com.app.maria.domain.inbound.exception.InboundNotFoundException;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.inbound.type.InboundZeroApprovalReason;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.domain.registrablestock.type.GeneralAccountType;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.mapper.SellOrderMapper;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.response.ApiResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class InboundServiceImplTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long CUSTOMER_ID = 5177L;
    private static final String CI_HASH = "ci-hash-5177";
    private static final Long FOREIGN_PRODUCT_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

    @Mock private InboundMapper inboundMapper;

    @Mock private ForeignProductMapper foreignProductMapper;

    @Mock private AccountMapper accountMapper;

    @Mock private SellOrderMapper sellOrderMapper;

    @Mock private RestClient restClient;

    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;

    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private BusinessClockService businessClockService;

    @InjectMocks private InboundServiceImpl inboundService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpRestClientChain() {
        lenient()
                .when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder()
                                        .accountId(ACCOUNT_ID)
                                        .customerId(CUSTOMER_ID)
                                        .build()));
        lenient()
                .when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(CI_HASH));
        lenient().when(restClient.get()).thenReturn(requestHeadersUriSpec);
        lenient()
                .when(
                        requestHeadersUriSpec.uri(
                                eq(
                                        "/api/registrable-stocks?ciHash={ciHash}&foreignProductId={foreignProductId}"),
                                eq(CI_HASH),
                                eq(FOREIGN_PRODUCT_ID)))
                .thenReturn(requestHeadersSpec);
        lenient()
                .when(
                        requestHeadersUriSpec.uri(
                                eq(
                                        "/api/registrable-stocks/lots?ciHash={ciHash}&foreignProductId={foreignProductId}"),
                                eq(CI_HASH),
                                eq(FOREIGN_PRODUCT_ID)))
                .thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(businessClockService.now()).thenReturn(NOW);
        lenient()
                .when(
                        inboundMapper.sumApprovedQtyBySourceGeneralAccount(
                                ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(List.of());
    }

    @Test
    void processInboundApprovesRequestedQtyWhenWithinAllLimits() {
        stubRegistrableStock(BigDecimal.valueOf(100));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);

        InboundResponseDTO result =
                inboundService.processInbound(
                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(90)));

        assertThat(result.getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(80));
        assertThat(result.getSnapshotQty()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void processInboundCapsApprovedQtyBySnapshotQtyWhenRequestedExceedsSnapshot() {
        stubRegistrableStock(BigDecimal.valueOf(50));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);

        InboundResponseDTO result =
                inboundService.processInbound(
                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(90)));

        assertThat(result.getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void processInboundCapsApprovedQtyByCurrentHoldingWhenLowerThanOtherLimits() {
        stubRegistrableStock(BigDecimal.valueOf(100));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);

        InboundResponseDTO result =
                inboundService.processInbound(
                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(30)));

        assertThat(result.getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    void processInboundReducesAvailableQtyByPreviouslyApprovedQty() {
        stubRegistrableStock(BigDecimal.valueOf(100));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.valueOf(60));

        InboundResponseDTO result =
                inboundService.processInbound(
                        request(BigDecimal.valueOf(50), BigDecimal.valueOf(90)));

        // availableQty = 100 - 60 = 40, requestedQty(50)와 currentHolding(90)보다 작음
        assertThat(result.getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(40));
    }

    @Test
    void processInboundApprovesZeroWhenPreviouslyApprovedQtyReachesSnapshot() {
        stubRegistrableStock(BigDecimal.valueOf(100));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.valueOf(100));

        InboundResponseDTO result =
                inboundService.processInbound(
                        request(BigDecimal.valueOf(50), BigDecimal.valueOf(90)));

        assertThat(result.getApprovedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getZeroApprovalReason())
                .isEqualTo(InboundZeroApprovalReason.SNAPSHOT_QUANTITY_EXHAUSTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processInboundThrowsNotFoundWhenApiResponseIsNull() {
        when(responseSpec.body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenReturn(null);

        assertThatThrownBy(
                        () ->
                                inboundService.processInbound(
                                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(90))))
                .isInstanceOf(InboundNotFoundException.class)
                .hasMessage("등록가능 보유수량 조회 실패");
    }

    @Test
    @SuppressWarnings("unchecked")
    void processInboundThrowsNotFoundWhenApiResponseDataIsNull() {
        ApiResponseDTO<RegistrableStockResponseDTO> apiResponse = ApiResponseDTO.of("조회 실패", null);
        when(responseSpec.body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenReturn(apiResponse);

        assertThatThrownBy(
                        () ->
                                inboundService.processInbound(
                                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(90))))
                .isInstanceOf(InboundNotFoundException.class)
                .hasMessage("등록가능 보유수량 조회 실패");
    }

    @Test
    void processInboundThrowsAccountNotFoundWhenAccountDoesNotExist() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                inboundService.processInbound(
                                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(90))))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("입고 대상 계좌가 존재하지 않습니다.");
    }

    @Test
    void processInboundThrowsAccountNotFoundWhenCiHashMissing() {
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                inboundService.processInbound(
                                        request(BigDecimal.valueOf(80), BigDecimal.valueOf(90))))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("입고 계좌의 고객 식별정보를 찾을 수 없습니다.");
    }

    @Test
    void processInboundResolvesCiHashFromAccountBeforeCallingRegistrableStockApi() {
        stubRegistrableStock(BigDecimal.valueOf(100));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);

        inboundService.processInbound(request(BigDecimal.valueOf(80), BigDecimal.valueOf(90)));

        verify(accountMapper).selectByAccountId(ACCOUNT_ID);
        verify(accountMapper).selectCiHashByCustomerId(CUSTOMER_ID);
        verify(requestHeadersUriSpec)
                .uri(
                        "/api/registrable-stocks?ciHash={ciHash}&foreignProductId={foreignProductId}",
                        CI_HASH,
                        FOREIGN_PRODUCT_ID);
    }

    @Test
    void processInboundSetsSourceGeneralAccountIdFromRegistrableStockResponse() {
        stubRegistrableStock(BigDecimal.valueOf(100), 42L);
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        inboundService.processInbound(request(BigDecimal.valueOf(80), BigDecimal.valueOf(90)));

        verify(inboundMapper).insertInboundDetail(captor.capture());
        // 증권사 registrable-stock 응답의 generalAccountId를 그대로 써야 함
        // (예전엔 여기 RIA account_id가 잘못 들어갔었음 - 회귀 방지용 테스트)
        assertThat(captor.getValue().getSourceGeneralAccountId()).isEqualTo(42L);
    }

    @Test
    void processInboundSetsAccountTypeFromRegistrableStockResponse() {
        RegistrableStockResponseDTO registrableStock =
                RegistrableStockResponseDTO.builder()
                        .generalAccountId(42L)
                        .accountType(GeneralAccountType.IRP)
                        .heldQty(BigDecimal.valueOf(100))
                        .purchaseDate(LocalDateTime.now())
                        .purchasePrice(BigDecimal.valueOf(150.25))
                        .purchaseCurrency("USD")
                        .purchaseFxRate(BigDecimal.valueOf(1320.5))
                        .build();
        stubRegistrableStockLots(BigDecimal.valueOf(100), List.of(registrableStock));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        inboundService.processInbound(request(BigDecimal.valueOf(80), BigDecimal.valueOf(90)));

        verify(inboundMapper).insertInboundDetail(captor.capture());
        assertThat(captor.getValue().getAccountType()).isEqualTo(GeneralAccountType.IRP);
    }

    @Test
    void processInboundSplitsAcrossMultipleLotsFifoByPurchaseDate() {
        RegistrableStockResponseDTO irpLot =
                lot(10L, BigDecimal.valueOf(40), LocalDateTime.of(2026, 1, 5, 9, 0));
        RegistrableStockResponseDTO brokerageLot =
                lot(20L, BigDecimal.valueOf(60), LocalDateTime.of(2026, 3, 10, 9, 0));
        stubRegistrableStockLots(BigDecimal.valueOf(100), List.of(irpLot, brokerageLot));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        // IRP 40주(1월 매수, 먼저 채움) + 종합위탁 60주(3월 매수) 중 40주만 = 총 80주 승인
        inboundService.processInbound(request(BigDecimal.valueOf(80), BigDecimal.valueOf(90)));

        verify(inboundMapper, times(2)).insertInboundDetail(captor.capture());
        List<InboundDetailDTO> details = captor.getAllValues();
        assertThat(details.get(0).getSourceGeneralAccountId()).isEqualTo(10L);
        assertThat(details.get(0).getQty()).isEqualByComparingTo(BigDecimal.valueOf(40));
        assertThat(details.get(1).getSourceGeneralAccountId()).isEqualTo(20L);
        assertThat(details.get(1).getQty()).isEqualByComparingTo(BigDecimal.valueOf(40));
    }

    @Test
    void processInboundExcludesAlreadyApprovedQtyPerLotFromAllocation() {
        RegistrableStockResponseDTO irpLot =
                lot(10L, BigDecimal.valueOf(40), LocalDateTime.of(2026, 1, 5, 9, 0));
        RegistrableStockResponseDTO brokerageLot =
                lot(20L, BigDecimal.valueOf(60), LocalDateTime.of(2026, 3, 10, 9, 0));
        stubRegistrableStockLots(BigDecimal.valueOf(100), List.of(irpLot, brokerageLot));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.valueOf(30));
        when(inboundMapper.sumApprovedQtyBySourceGeneralAccount(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(
                        List.of(
                                SourceLotApprovedQtyDTO.builder()
                                        .generalAccountId(10L)
                                        .approvedQty(BigDecimal.valueOf(30))
                                        .build()));
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        // availableQty = 100 - 30 = 70 -> 70주 그대로 승인
        // IRP는 40주 중 이미 30주 승인돼서 10주만 남음, 나머지 60주는 종합위탁에서
        inboundService.processInbound(request(BigDecimal.valueOf(70), BigDecimal.valueOf(90)));

        verify(inboundMapper, times(2)).insertInboundDetail(captor.capture());
        List<InboundDetailDTO> details = captor.getAllValues();
        assertThat(details.get(0).getSourceGeneralAccountId()).isEqualTo(10L);
        assertThat(details.get(0).getQty()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(details.get(1).getSourceGeneralAccountId()).isEqualTo(20L);
        assertThat(details.get(1).getQty()).isEqualByComparingTo(BigDecimal.valueOf(60));
    }

    @Test
    void processInboundAppliesAlreadyUsedOnceAcrossMultipleLotsInSameGeneralAccount() {
        RegistrableStockResponseDTO firstLot =
                lot(10L, BigDecimal.valueOf(40), LocalDateTime.of(2026, 1, 5, 9, 0));
        RegistrableStockResponseDTO secondLot =
                lot(10L, BigDecimal.valueOf(30), LocalDateTime.of(2026, 2, 10, 9, 0));
        stubRegistrableStockLots(BigDecimal.valueOf(100), List.of(firstLot, secondLot));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);
        when(inboundMapper.sumApprovedQtyBySourceGeneralAccount(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(
                        List.of(
                                SourceLotApprovedQtyDTO.builder()
                                        .generalAccountId(10L)
                                        .approvedQty(BigDecimal.valueOf(20))
                                        .build()));
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        // 같은 general_account(10L)에 lot 2개(40+30=70 보유), 이미 20 승인됨 -> 실제 잔여는 70-20=50
        // (버그 있었을 때는 alreadyUsed 20이 각 lot에서 중복 차감돼 lot1=20, lot2=10만 잡혀 총 30만 승인됨)
        inboundService.processInbound(request(BigDecimal.valueOf(50), BigDecimal.valueOf(90)));

        verify(inboundMapper, times(2)).insertInboundDetail(captor.capture());
        List<InboundDetailDTO> details = captor.getAllValues();
        assertThat(details.get(0).getSourceGeneralAccountId()).isEqualTo(10L);
        assertThat(details.get(0).getQty()).isEqualByComparingTo(BigDecimal.valueOf(20));
        assertThat(details.get(1).getSourceGeneralAccountId()).isEqualTo(10L);
        assertThat(details.get(1).getQty()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    void processInboundCreatesSingleZeroQtyDetailWhenApprovedQtyIsZero() {
        RegistrableStockResponseDTO irpLot =
                lot(10L, BigDecimal.valueOf(40), LocalDateTime.of(2026, 1, 5, 9, 0));
        stubRegistrableStockLots(BigDecimal.valueOf(40), List.of(irpLot));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        // currentHoldingAtRequest = 0 -> approvedQty = 0 (반려)이어도 lot 1건은 기록돼야 함
        InboundResponseDTO result =
                inboundService.processInbound(request(BigDecimal.valueOf(80), BigDecimal.ZERO));

        verify(inboundMapper, times(1)).insertInboundDetail(captor.capture());
        assertThat(captor.getValue().getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getSourceGeneralAccountId()).isEqualTo(10L);
        assertThat(result.getZeroApprovalReason())
                .isEqualTo(InboundZeroApprovalReason.CURRENT_HOLDING_INSUFFICIENT);
    }

    @Test
    void processInboundReturnsRequestedZeroReasonWhenRequestedQtyIsZero() {
        stubRegistrableStock(BigDecimal.valueOf(100));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);

        InboundResponseDTO result =
                inboundService.processInbound(request(BigDecimal.ZERO, BigDecimal.valueOf(90)));

        assertThat(result.getApprovedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getZeroApprovalReason())
                .isEqualTo(InboundZeroApprovalReason.REQUESTED_ZERO);
    }

    @Test
    void processInboundSkipsExhaustedEarlyLotWithoutCreatingZeroQtyDetail() {
        RegistrableStockResponseDTO irpLot =
                lot(10L, BigDecimal.valueOf(40), LocalDateTime.of(2026, 1, 5, 9, 0));
        RegistrableStockResponseDTO brokerageLot =
                lot(20L, BigDecimal.valueOf(60), LocalDateTime.of(2026, 3, 10, 9, 0));
        stubRegistrableStockLots(BigDecimal.valueOf(100), List.of(irpLot, brokerageLot));
        when(inboundMapper.sumApprovedQtyByAccountAndProduct(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(BigDecimal.ZERO);
        // FIFO상 먼저 도는 IRP(10L) lot이 이미 40주 전부 다른 건으로 소진된 상태
        when(inboundMapper.sumApprovedQtyBySourceGeneralAccount(ACCOUNT_ID, FOREIGN_PRODUCT_ID))
                .thenReturn(
                        List.of(
                                SourceLotApprovedQtyDTO.builder()
                                        .generalAccountId(10L)
                                        .approvedQty(BigDecimal.valueOf(40))
                                        .build()));
        ArgumentCaptor<InboundDetailDTO> captor = ArgumentCaptor.forClass(InboundDetailDTO.class);

        // IRP는 소진(가용 0)이라 건너뛰고 종합위탁(20L)에서만 30주 승인돼야 함
        // (버그 있었을 때는 IRP에 qty=0 쓰레기 행이 먼저 만들어지고 종합위탁 행이 추가로 또 만들어져 총 2건이었음)
        inboundService.processInbound(request(BigDecimal.valueOf(30), BigDecimal.valueOf(90)));

        verify(inboundMapper, times(1)).insertInboundDetail(captor.capture());
        InboundDetailDTO detail = captor.getValue();
        assertThat(detail.getSourceGeneralAccountId()).isEqualTo(20L);
        assertThat(detail.getQty()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    void getHoldingsEnrichesEachHoldingWithProductInfo() {
        when(inboundMapper.selectHoldingsByAccount(ACCOUNT_ID))
                .thenReturn(List.of(holding(FOREIGN_PRODUCT_ID, BigDecimal.valueOf(50))));
        when(foreignProductMapper.selectById(FOREIGN_PRODUCT_ID))
                .thenReturn(Optional.of(foreignProduct(FOREIGN_PRODUCT_ID)));

        List<AccountHoldingResponseDTO> result = inboundService.getHoldings(ACCOUNT_ID);

        assertThat(result).hasSize(1);
        AccountHoldingResponseDTO first = result.get(0);
        assertThat(first.getForeignProductId()).isEqualTo(FOREIGN_PRODUCT_ID);
        assertThat(first.getTicker()).isEqualTo("AAPL");
        assertThat(first.getName()).isEqualTo("Apple Inc.");
        assertThat(first.getMarket()).isEqualTo("NAS");
        assertThat(first.getCurrency()).isEqualTo("USD");
        assertThat(first.getType()).isEqualTo(ForeignProductType.FOREIGN_STOCK);
        assertThat(first.getCurrentQty()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void getHoldingsReturnsOneEntryPerHoldingInMapperOrder() {
        when(inboundMapper.selectHoldingsByAccount(ACCOUNT_ID))
                .thenReturn(
                        List.of(
                                holding(1L, BigDecimal.valueOf(10)),
                                holding(2L, BigDecimal.valueOf(20))));
        when(foreignProductMapper.selectById(1L)).thenReturn(Optional.of(foreignProduct(1L)));
        when(foreignProductMapper.selectById(2L)).thenReturn(Optional.of(foreignProduct(2L)));

        List<AccountHoldingResponseDTO> result = inboundService.getHoldings(ACCOUNT_ID);

        assertThat(result)
                .extracting(AccountHoldingResponseDTO::getForeignProductId)
                .containsExactly(1L, 2L);
    }

    @Test
    void getHoldingsReturnsEmptyListWhenAccountHasNoHoldings() {
        when(inboundMapper.selectHoldingsByAccount(ACCOUNT_ID)).thenReturn(List.of());

        List<AccountHoldingResponseDTO> result = inboundService.getHoldings(ACCOUNT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void getHoldingsThrowsWhenHeldProductNoLongerExists() {
        when(inboundMapper.selectHoldingsByAccount(ACCOUNT_ID))
                .thenReturn(List.of(holding(FOREIGN_PRODUCT_ID, BigDecimal.valueOf(50))));
        when(foreignProductMapper.selectById(FOREIGN_PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inboundService.getHoldings(ACCOUNT_ID))
                .isInstanceOf(ForeignProductNotFoundException.class)
                .hasMessage("종목 정보를 찾을 수 없습니다.");
    }

    @Test
    void getInboundsCalculatesOffsetAndDelegatesToMapper() {
        List<InboundListDTO> expected = List.of(InboundListDTO.builder().inboundId(1L).build());
        when(inboundMapper.selectInbounds(20, 10)).thenReturn(expected);
        when(inboundMapper.countInbounds()).thenReturn(25);

        InboundPageDTO result = inboundService.getInbounds(2, 10);

        verify(inboundMapper).selectInbounds(20, 10);
        assertThat(result.getContent()).isEqualTo(expected);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
    }

    @Test
    void getInboundsCalculatesTotalPagesFromTotalElements() {
        when(inboundMapper.selectInbounds(0, 20)).thenReturn(List.of());
        when(inboundMapper.countInbounds()).thenReturn(45);

        InboundPageDTO result = inboundService.getInbounds(0, 20);

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    void getInboundsReturnsZeroTotalPagesWhenNoRows() {
        when(inboundMapper.selectInbounds(0, 20)).thenReturn(List.of());
        when(inboundMapper.countInbounds()).thenReturn(0);

        InboundPageDTO result = inboundService.getInbounds(0, 20);

        assertThat(result.getTotalElements()).isEqualTo(0);
        assertThat(result.getTotalPages()).isEqualTo(0);
    }

    @Test
    void getInboundsAttachesSellHistoryToMatchingLot() {
        InboundListDTO item = InboundListDTO.builder().inboundId(1L).build();
        InboundLotDTO lot = InboundLotDTO.builder().inboundId(1L).inboundDetailId(100L).build();
        SellOrderDTO sellOrder =
                SellOrderDTO.builder()
                        .orderId(1L)
                        .inboundDetailId(100L)
                        .sellQty(BigDecimal.valueOf(10))
                        .basePrice(BigDecimal.valueOf(150))
                        .status(SellOrderStatus.EXECUTED)
                        .processedAt(NOW)
                        .build();
        when(inboundMapper.selectInbounds(0, 20)).thenReturn(List.of(item));
        when(inboundMapper.selectLotsByInboundIds(List.of(1L))).thenReturn(List.of(lot));
        when(sellOrderMapper.selectSellOrdersByInboundDetailIds(List.of(100L)))
                .thenReturn(List.of(sellOrder));
        when(inboundMapper.countInbounds()).thenReturn(1);

        InboundPageDTO result = inboundService.getInbounds(0, 20);

        List<InboundLotDTO> lots = result.getContent().get(0).getLots();
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).getSellHistory()).hasSize(1);
        assertThat(lots.get(0).getSellHistory().get(0).getSellQty())
                .isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(lots.get(0).getSellHistory().get(0).getStatus())
                .isEqualTo(SellOrderStatus.EXECUTED);
    }

    @Test
    void getInboundsSetsEmptySellHistoryWhenNoSellOrdersExist() {
        InboundListDTO item = InboundListDTO.builder().inboundId(1L).build();
        InboundLotDTO lot = InboundLotDTO.builder().inboundId(1L).inboundDetailId(100L).build();
        when(inboundMapper.selectInbounds(0, 20)).thenReturn(List.of(item));
        when(inboundMapper.selectLotsByInboundIds(List.of(1L))).thenReturn(List.of(lot));
        when(sellOrderMapper.selectSellOrdersByInboundDetailIds(List.of(100L)))
                .thenReturn(List.of());
        when(inboundMapper.countInbounds()).thenReturn(1);

        InboundPageDTO result = inboundService.getInbounds(0, 20);

        assertThat(result.getContent().get(0).getLots().get(0).getSellHistory()).isEmpty();
    }

    @Test
    void getInboundsDoesNotQuerySellOrdersWhenNoLotsExist() {
        when(inboundMapper.selectInbounds(0, 20)).thenReturn(List.of());
        when(inboundMapper.countInbounds()).thenReturn(0);

        inboundService.getInbounds(0, 20);

        verify(sellOrderMapper, never()).selectSellOrdersByInboundDetailIds(any());
    }

    @Test
    void getSummaryDelegatesToMapperUsingClockTodayRange() {
        InboundSummaryDTO summaryDTO =
                InboundSummaryDTO.builder()
                        .todayProcessedCount(3)
                        .todayRejectedCount(1)
                        .todayReducedCount(1)
                        .todayApprovedQtySum(BigDecimal.valueOf(70))
                        .build();
        when(inboundMapper.selectTodaySummary(NOW.toLocalDate(), NOW.toLocalDate().plusDays(1)))
                .thenReturn(summaryDTO);

        InboundSummaryResponseDTO result = inboundService.getSummary();

        verify(inboundMapper).selectTodaySummary(NOW.toLocalDate(), NOW.toLocalDate().plusDays(1));
        assertThat(result.getTodayProcessedCount()).isEqualTo(3);
        assertThat(result.getTodayRejectedCount()).isEqualTo(1);
        assertThat(result.getTodayReducedCount()).isEqualTo(1);
        assertThat(result.getTodayApprovedQtySum()).isEqualByComparingTo(BigDecimal.valueOf(70));
    }

    @Test
    void getAccountsWithInboundsMapsMapperResultToResponseDTOsWithPaginationMetadata() {
        InboundAccountSummaryDTO accountSummary =
                InboundAccountSummaryDTO.builder()
                        .accountId(ACCOUNT_ID)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .inboundCount(2)
                        .lastProcessedAt(NOW)
                        .build();
        when(inboundMapper.selectAccountsWithInbounds(0, 20, null))
                .thenReturn(List.of(accountSummary));
        when(inboundMapper.countAccountsWithInbounds(null)).thenReturn(1);

        var result = inboundService.getAccountsWithInbounds(0, 20, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getAccountNo()).isEqualTo("1234567890");
        assertThat(result.getContent().get(0).getInboundCount()).isEqualTo(2);
        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getPage()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(20);
    }

    @Test
    void getAccountsWithInboundsPassesKeywordThroughToMapper() {
        when(inboundMapper.selectAccountsWithInbounds(0, 20, "홍길동")).thenReturn(List.of());
        when(inboundMapper.countAccountsWithInbounds("홍길동")).thenReturn(0);

        inboundService.getAccountsWithInbounds(0, 20, "홍길동");

        verify(inboundMapper).selectAccountsWithInbounds(0, 20, "홍길동");
        verify(inboundMapper).countAccountsWithInbounds("홍길동");
    }

    @Test
    void getAccountsWithInboundsReturnsEmptyContentWhenNoAccountsExist() {
        when(inboundMapper.selectAccountsWithInbounds(0, 20, null)).thenReturn(List.of());
        when(inboundMapper.countAccountsWithInbounds(null)).thenReturn(0);

        var result = inboundService.getAccountsWithInbounds(0, 20, null);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalCount()).isEqualTo(0);
    }

    @Test
    void getInboundsByAccountDelegatesToMapperWithAccountIdAndAttachesLots() {
        InboundListDTO item = InboundListDTO.builder().inboundId(1L).accountId(ACCOUNT_ID).build();
        when(inboundMapper.selectInboundsByAccountId(ACCOUNT_ID, 0, 20)).thenReturn(List.of(item));
        when(inboundMapper.countInboundsByAccountId(ACCOUNT_ID)).thenReturn(1);
        when(inboundMapper.selectLotsByInboundIds(List.of(1L))).thenReturn(List.of());

        InboundPageDTO result = inboundService.getInboundsByAccount(ACCOUNT_ID, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getLots()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(inboundMapper).selectInboundsByAccountId(ACCOUNT_ID, 0, 20);
    }

    @Test
    void getInboundsByAccountReturnsEmptyContentWhenAccountHasNoInbounds() {
        when(inboundMapper.selectInboundsByAccountId(ACCOUNT_ID, 0, 20)).thenReturn(List.of());
        when(inboundMapper.countInboundsByAccountId(ACCOUNT_ID)).thenReturn(0);

        InboundPageDTO result = inboundService.getInboundsByAccount(ACCOUNT_ID, 0, 20);

        assertThat(result.getContent()).isEmpty();
        verify(sellOrderMapper, never()).selectSellOrdersByInboundDetailIds(any());
    }

    @Test
    void getPriorApprovalsMapsMapperResultToResponseDTOs() {
        InboundPriorApprovalDTO prior =
                InboundPriorApprovalDTO.builder()
                        .inboundId(1L)
                        .requestedQty(BigDecimal.valueOf(10))
                        .approvedQty(BigDecimal.valueOf(10))
                        .processedAt(NOW)
                        .build();
        when(inboundMapper.selectPriorApprovals(2L)).thenReturn(List.of(prior));

        List<InboundPriorApprovalResponseDTO> result = inboundService.getPriorApprovals(2L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getInboundId()).isEqualTo(1L);
        assertThat(result.get(0).getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(10));
    }

    @Test
    void getPriorApprovalsReturnsEmptyListWhenNoPriorApprovalsExist() {
        when(inboundMapper.selectPriorApprovals(2L)).thenReturn(List.of());

        List<InboundPriorApprovalResponseDTO> result = inboundService.getPriorApprovals(2L);

        assertThat(result).isEmpty();
    }

    private InboundHoldingDTO holding(Long foreignProductId, BigDecimal currentQty) {
        return InboundHoldingDTO.builder()
                .foreignProductId(foreignProductId)
                .currentQty(currentQty)
                .build();
    }

    private ForeignProductDTO foreignProduct(Long foreignProductId) {
        return ForeignProductDTO.builder()
                .foreignProductId(foreignProductId)
                .ticker("AAPL")
                .name("Apple Inc.")
                .market("NAS")
                .currency("USD")
                .foreignProductType(ForeignProductType.FOREIGN_STOCK)
                .build();
    }

    private void stubRegistrableStock(BigDecimal heldQty) {
        stubRegistrableStock(heldQty, null);
    }

    private void stubRegistrableStock(BigDecimal heldQty, Long generalAccountId) {
        RegistrableStockResponseDTO registrableStock =
                lot(generalAccountId, heldQty, LocalDateTime.now());
        stubRegistrableStockLots(heldQty, List.of(registrableStock));
    }

    @SuppressWarnings("unchecked")
    private void stubRegistrableStockLots(
            BigDecimal aggregateHeldQty, List<RegistrableStockResponseDTO> lots) {
        RegistrableStockResponseDTO aggregate =
                RegistrableStockResponseDTO.builder()
                        .heldQty(aggregateHeldQty)
                        .sourceBroker(null)
                        .purchaseDate(LocalDateTime.now())
                        .purchasePrice(BigDecimal.valueOf(150.25))
                        .purchaseCurrency("USD")
                        .purchaseFxRate(BigDecimal.valueOf(1320.5))
                        .build();
        ApiResponseDTO<RegistrableStockResponseDTO> apiResponse =
                ApiResponseDTO.of("등록가능 보유수량 조회 성공", aggregate);
        ApiResponseDTO<List<RegistrableStockResponseDTO>> lotsResponse =
                ApiResponseDTO.of("조회 성공", lots);
        when(responseSpec.body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenReturn(apiResponse, lotsResponse);
    }

    private RegistrableStockResponseDTO lot(
            Long generalAccountId, BigDecimal heldQty, LocalDateTime purchaseDate) {
        return RegistrableStockResponseDTO.builder()
                .generalAccountId(generalAccountId)
                .heldQty(heldQty)
                .sourceBroker(null)
                .purchaseDate(purchaseDate)
                .purchasePrice(BigDecimal.valueOf(150.25))
                .purchaseCurrency("USD")
                .purchaseFxRate(BigDecimal.valueOf(1320.5))
                .build();
    }

    private InboundRequestDTO request(BigDecimal requestedQty, BigDecimal currentHoldingAtRequest) {
        return InboundRequestDTO.builder()
                .accountId(ACCOUNT_ID)
                .foreignProductId(FOREIGN_PRODUCT_ID)
                .requestedQty(requestedQty)
                .currentHoldingAtRequest(currentHoldingAtRequest)
                .build();
    }
}
