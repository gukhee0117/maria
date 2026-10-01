package com.app.maria.domain.sellorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.dto.SellOrderHistoryDTO;
import com.app.maria.domain.sellorder.dto.SellOrderSummaryDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.mapper.SellOrderMapper;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.domain.settlement.service.ProvisionalExchangeService;
import com.app.maria.domain.settlement.service.SettlementService;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellOrderServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 9, 12, 0);
    private static final Long ACTOR_ADMIN_ID = 99L;

    @Mock SellOrderMapper sellOrderMapper;

    @Mock KisPriceClient kisPriceClient;

    @Mock ExchangeRateClient exchangeRateClient;

    @Mock InboundMapper inboundMapper;

    @Mock ForeignProductMapper foreignProductMapper;

    @Mock SellLimitService sellLimitService;

    @Mock BusinessClockService businessClockService;

    @Mock ProvisionalExchangeService provisionalExchangeService;

    @Mock SettlementService settlementService;

    @InjectMocks SellOrderServiceImpl sellOrderService;

    private SellOrderRequestDTO.SellOrderRequestDTOBuilder validRequestBuilder() {
        return SellOrderRequestDTO.builder()
                .accountId(1L)
                .foreignProductId(10L)
                .sellQty(new BigDecimal("10"));
    }

    private InboundDetailDTO lot(
            Long inboundDetailId, String currentQty, LocalDateTime purchaseDate) {
        return InboundDetailDTO.builder()
                .inboundDetailId(inboundDetailId)
                .foreignProductId(10L)
                .currentQty(new BigDecimal(currentQty))
                .purchaseDate(purchaseDate)
                .build();
    }

    private ForeignProductDTO validProduct() {
        return ForeignProductDTO.builder()
                .foreignProductId(10L)
                .market("NASDAQ")
                .ticker("AAPL")
                .currency("USD")
                .build();
    }

    private void stubPriceAndRate() {
        when(kisPriceClient.getPreviousClose("NAS", "AAPL")).thenReturn(new BigDecimal("308.91"));
        when(exchangeRateClient.getBaseRate("USD")).thenReturn(new BigDecimal("1433.6"));
    }

    @Test
    @DisplayName("한도 이내면서 lot 하나로 전량 체결되면 잔량을 차감하고 전일종가와 환율을 곱해 EXECUTED 상태 1건으로 저장한다")
    void placeSellOrderDecreasesQtyAndSavesAsExecutedWhenSingleLotCoversFullQty() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(inboundMapper.selectFifoLots(1L, 10L))
                .thenReturn(List.of(lot(1L, "50", LocalDateTime.of(2026, 1, 1, 0, 0))));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        stubPriceAndRate();
        when(sellLimitService.isWithinSellLimit(any(), any())).thenReturn(true);
        when(inboundMapper.decreaseCurrentQty(1L, new BigDecimal("10"))).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        BigDecimal expectedBasePrice = new BigDecimal("308.91").multiply(new BigDecimal("1433.6"));

        List<SellOrderResponseDTO> result =
                sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request);

        assertThat(result).hasSize(1);
        SellOrderResponseDTO first = result.get(0);
        assertThat(first.getInboundDetailId()).isEqualTo(1L);
        assertThat(first.getSellQty()).isEqualByComparingTo("10");
        assertThat(first.getStatus()).isEqualTo(SellOrderStatus.EXECUTED);
        assertThat(first.getBasePrice()).isEqualByComparingTo(expectedBasePrice);
        assertThat(first.getSettlementFxRate()).isEqualByComparingTo("1433.6");
        assertThat(first.getProcessedAt()).isEqualTo(NOW);

        verify(inboundMapper, times(1)).decreaseCurrentQty(1L, new BigDecimal("10"));

        ArgumentCaptor<SellOrderDTO> captor = ArgumentCaptor.forClass(SellOrderDTO.class);
        verify(sellOrderMapper, times(1)).insertSellOrder(captor.capture());
        SellOrderDTO saved = captor.getValue();
        assertThat(saved.getInboundDetailId()).isEqualTo(1L);
        assertThat(saved.getSellQty()).isEqualByComparingTo("10");
        assertThat(saved.getStatus()).isEqualTo(SellOrderStatus.EXECUTED);
        assertThat(saved.getBasePrice()).isEqualByComparingTo(expectedBasePrice);
        assertThat(saved.getSettlementFxRate()).isEqualByComparingTo("1433.6");
        assertThat(saved.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("한 lot의 잔량으로 부족하면 오래된 lot부터 순서대로 나눠 체결하고 lot마다 매도 주문을 하나씩 저장한다")
    void placeSellOrderSplitsAcrossMultipleLotsInFifoOrderWhenSingleLotIsInsufficient() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        InboundDetailDTO olderLot = lot(1L, "6", LocalDateTime.of(2026, 1, 1, 0, 0));
        InboundDetailDTO newerLot = lot(2L, "50", LocalDateTime.of(2026, 2, 1, 0, 0));
        when(inboundMapper.selectFifoLots(1L, 10L)).thenReturn(List.of(olderLot, newerLot));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        stubPriceAndRate();
        when(sellLimitService.isWithinSellLimit(any(), any())).thenReturn(true);
        when(inboundMapper.decreaseCurrentQty(1L, new BigDecimal("6"))).thenReturn(1);
        when(inboundMapper.decreaseCurrentQty(2L, new BigDecimal("4"))).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);

        List<SellOrderResponseDTO> result =
                sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getInboundDetailId()).isEqualTo(1L);
        assertThat(result.get(0).getSellQty()).isEqualByComparingTo("6");
        assertThat(result.get(1).getInboundDetailId()).isEqualTo(2L);
        assertThat(result.get(1).getSellQty()).isEqualByComparingTo("4");
        assertThat(result)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(SellOrderStatus.EXECUTED));

        verify(inboundMapper, times(1)).decreaseCurrentQty(1L, new BigDecimal("6"));
        verify(inboundMapper, times(1)).decreaseCurrentQty(2L, new BigDecimal("4"));
        verify(sellOrderMapper, times(2)).insertSellOrder(any());
    }

    @Test
    @DisplayName("정상 요청이면 sellLimitService에 계좌ID와 매도금액(원요청 수량x기준가) 전체를 정확히 한 번만 넘겨 한도를 검증한다")
    void placeSellOrderCallsSellLimitServiceOnceWithAccountIdAndTotalOrderAmount() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        InboundDetailDTO olderLot = lot(1L, "6", LocalDateTime.of(2026, 1, 1, 0, 0));
        InboundDetailDTO newerLot = lot(2L, "50", LocalDateTime.of(2026, 2, 1, 0, 0));
        when(inboundMapper.selectFifoLots(1L, 10L)).thenReturn(List.of(olderLot, newerLot));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        stubPriceAndRate();
        when(sellLimitService.isWithinSellLimit(any(), any())).thenReturn(true);
        when(inboundMapper.decreaseCurrentQty(any(), any())).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        BigDecimal expectedOrderAmount =
                new BigDecimal("10")
                        .multiply(new BigDecimal("308.91").multiply(new BigDecimal("1433.6")));

        sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request);

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(sellLimitService, times(1)).isWithinSellLimit(eq(1L), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(expectedOrderAmount);
    }

    @Test
    @DisplayName(
            "한도초과로 확인되면 lot 차감 없이 inboundDetailId가 없는 REJECTED 단건으로 저장한다 (basePrice/fxRate는 그대로 기록)")
    void placeSellOrderSavesAsRejectedWithoutDecreasingAnyLotWhenSellLimitExceeded() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        InboundDetailDTO olderLot = lot(1L, "6", LocalDateTime.of(2026, 1, 1, 0, 0));
        InboundDetailDTO newerLot = lot(2L, "50", LocalDateTime.of(2026, 2, 1, 0, 0));
        when(inboundMapper.selectFifoLots(1L, 10L)).thenReturn(List.of(olderLot, newerLot));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        stubPriceAndRate();
        when(sellLimitService.isWithinSellLimit(any(), any())).thenReturn(false);
        when(businessClockService.now()).thenReturn(NOW);
        BigDecimal expectedBasePrice = new BigDecimal("308.91").multiply(new BigDecimal("1433.6"));

        List<SellOrderResponseDTO> result =
                sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request);

        assertThat(result).hasSize(1);
        SellOrderResponseDTO rejected = result.get(0);
        assertThat(rejected.getInboundDetailId()).isNull();
        assertThat(rejected.getStatus()).isEqualTo(SellOrderStatus.REJECTED);
        assertThat(rejected.getProcessedAt()).isEqualTo(NOW);
        assertThat(rejected.getBasePrice()).isEqualByComparingTo(expectedBasePrice);
        assertThat(rejected.getSettlementFxRate()).isEqualByComparingTo("1433.6");

        verify(inboundMapper, never()).decreaseCurrentQty(any(), any());

        ArgumentCaptor<SellOrderDTO> captor = ArgumentCaptor.forClass(SellOrderDTO.class);
        verify(sellOrderMapper, times(1)).insertSellOrder(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SellOrderStatus.REJECTED);
        assertThat(captor.getValue().getInboundDetailId()).isNull();
    }

    @Test
    @DisplayName("전일종가 조회에 실패하면 예외가 전파되고 한도판정/저장/차감은 하지 않는다")
    void placeSellOrderPropagatesExceptionAndSkipsSaveWhenPreviousCloseLookupFails() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(inboundMapper.selectFifoLots(1L, 10L))
                .thenReturn(List.of(lot(1L, "50", LocalDateTime.of(2026, 1, 1, 0, 0))));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        when(kisPriceClient.getPreviousClose("NAS", "AAPL"))
                .thenThrow(new AppException(ErrorType.KIS_PRICE_NOT_FOUND, "AAPL"));

        assertThatThrownBy(() -> sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.KIS_PRICE_NOT_FOUND.getMessage());

        verify(exchangeRateClient, never()).getBaseRate(any());
        verifyNoInteractions(sellOrderMapper, sellLimitService);
        verify(inboundMapper, never()).decreaseCurrentQty(any(), any());
    }

    @Test
    @DisplayName("보유 중인 lot이 하나도 없으면 매도 가능 수량 초과로 예외를 던지고 종목 조회/외부 API/저장은 하지 않는다")
    void placeSellOrderThrowsWhenNoLotsExist() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(inboundMapper.selectFifoLots(1L, 10L)).thenReturn(List.of());

        assertThatThrownBy(() -> sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessage("매도 가능 수량을 초과했습니다.");

        verify(inboundMapper, never()).decreaseCurrentQty(any(), any());
        verifyNoInteractions(
                kisPriceClient,
                exchangeRateClient,
                sellOrderMapper,
                foreignProductMapper,
                sellLimitService);
    }

    @Test
    @DisplayName("매도 수량이 보유 lot들의 currentQty 합계를 초과하면 예외를 던지고 종목 조회/외부 API/저장은 하지 않는다")
    void placeSellOrderThrowsWhenSellQtyExceedsTotalCurrentQtyAcrossLots() {
        SellOrderRequestDTO request = validRequestBuilder().sellQty(new BigDecimal("100")).build();
        when(inboundMapper.selectFifoLots(1L, 10L))
                .thenReturn(List.of(lot(1L, "50", LocalDateTime.of(2026, 1, 1, 0, 0))));

        assertThatThrownBy(() -> sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessage("매도 가능 수량을 초과했습니다.");

        verify(inboundMapper, never()).decreaseCurrentQty(any(), any());
        verifyNoInteractions(
                kisPriceClient,
                exchangeRateClient,
                sellOrderMapper,
                foreignProductMapper,
                sellLimitService);
    }

    @Test
    @DisplayName("종목 정보를 찾을 수 없으면 예외를 던지고 잔량 차감/외부 API/저장은 하지 않는다")
    void placeSellOrderThrowsWhenForeignProductNotFound() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(inboundMapper.selectFifoLots(1L, 10L))
                .thenReturn(List.of(lot(1L, "50", LocalDateTime.of(2026, 1, 1, 0, 0))));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request))
                .isInstanceOf(ForeignProductNotFoundException.class)
                .hasMessage("종목 정보를 찾을 수 없습니다.");

        verify(inboundMapper, never()).decreaseCurrentQty(any(), any());
        verifyNoInteractions(kisPriceClient, exchangeRateClient, sellOrderMapper, sellLimitService);
    }

    @Test
    @DisplayName("한도 이내로 확인됐는데 첫 lot에서 동시성 충돌로 차감된 row가 없으면 예외를 던지고 그 lot은 저장하지 않는다")
    void placeSellOrderThrowsWhenDecreaseCurrentQtyAffectsNoRowsOnFirstLot() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(inboundMapper.selectFifoLots(1L, 10L))
                .thenReturn(List.of(lot(1L, "50", LocalDateTime.of(2026, 1, 1, 0, 0))));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        stubPriceAndRate();
        when(sellLimitService.isWithinSellLimit(any(), any())).thenReturn(true);
        when(inboundMapper.decreaseCurrentQty(1L, new BigDecimal("10"))).thenReturn(0);

        assertThatThrownBy(() -> sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessage("다른 요청이 먼저 처리되었습니다.");

        verifyNoInteractions(sellOrderMapper);
    }

    @Test
    @DisplayName(
            "두 번째 lot 처리 중 동시성 충돌이 나면 첫 번째 lot은 이미 저장된 채로 예외가 전파된다 (트랜잭션 롤백은 @Transactional이 담당)")
    void placeSellOrderPropagatesExceptionAfterFirstLotAlreadySavedWhenSecondLotConflicts() {
        SellOrderRequestDTO request = validRequestBuilder().build();

        InboundDetailDTO olderLot = lot(1L, "6", LocalDateTime.of(2026, 1, 1, 0, 0));
        InboundDetailDTO newerLot = lot(2L, "50", LocalDateTime.of(2026, 2, 1, 0, 0));
        when(inboundMapper.selectFifoLots(1L, 10L)).thenReturn(List.of(olderLot, newerLot));
        when(foreignProductMapper.selectById(10L)).thenReturn(Optional.of(validProduct()));
        stubPriceAndRate();
        when(sellLimitService.isWithinSellLimit(any(), any())).thenReturn(true);
        when(inboundMapper.decreaseCurrentQty(1L, new BigDecimal("6"))).thenReturn(1);
        when(inboundMapper.decreaseCurrentQty(2L, new BigDecimal("4"))).thenReturn(0);
        when(businessClockService.now()).thenReturn(NOW);

        assertThatThrownBy(() -> sellOrderService.placeSellOrder(ACTOR_ADMIN_ID, request))
                .isInstanceOf(AppException.class)
                .hasMessage("다른 요청이 먼저 처리되었습니다.");

        verify(sellOrderMapper, times(1)).insertSellOrder(any());
    }

    @Test
    @DisplayName("존재하면 조회 결과를 반환한다")
    void getSellOrderReturnsResultWhenExists() {
        SellOrderDTO saved =
                SellOrderDTO.builder()
                        .orderId(100L)
                        .inboundDetailId(1L)
                        .sellQty(new BigDecimal("10"))
                        .status(SellOrderStatus.EXECUTED)
                        .build();
        when(sellOrderMapper.selectSellOrderById(100L)).thenReturn(Optional.of(saved));

        SellOrderResponseDTO result = sellOrderService.getSellOrder(100L);

        assertThat(result.getOrderId()).isEqualTo(100L);
        assertThat(result.getInboundDetailId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(SellOrderStatus.EXECUTED);
    }

    @Test
    @DisplayName("존재하지 않으면 SellOrderNotFoundException을 던진다")
    void getSellOrderThrowsSellOrderNotFoundExceptionWhenNotFound() {
        when(sellOrderMapper.selectSellOrderById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellOrderService.getSellOrder(999L))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("page와 size로 offset을 계산해서 매퍼에 넘기고, 목록/건수를 PageResponseDTO로 감싸 반환한다")
    void getSellOrderHistoryComputesOffsetAndWrapsMapperResultsInPageResponse() {
        List<SellOrderHistoryDTO> content =
                List.of(SellOrderHistoryDTO.builder().accountNo("1000000001").build());
        when(sellOrderMapper.selectSellOrderHistory(null, null, null, null, 40, 20))
                .thenReturn(content);
        when(sellOrderMapper.countSellOrderHistory(null, null, null, null)).thenReturn(45);

        PageResponseDTO<SellOrderHistoryDTO> result =
                sellOrderService.getSellOrderHistory(null, null, null, null, 2, 20);

        assertThat(result.getContent()).isEqualTo(content);
        assertThat(result.getTotalCount()).isEqualTo(45);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
        verify(sellOrderMapper).selectSellOrderHistory(null, null, null, null, 40, 20);
        verify(sellOrderMapper).countSellOrderHistory(null, null, null, null);
    }

    @Test
    @DisplayName("keyword 필터를 그대로 매퍼에 전달한다")
    void getSellOrderHistoryPassesKeywordThroughToMapper() {
        when(sellOrderMapper.selectSellOrderHistory("1234567890", null, null, null, 0, 20))
                .thenReturn(List.of());
        when(sellOrderMapper.countSellOrderHistory("1234567890", null, null, null)).thenReturn(0);

        sellOrderService.getSellOrderHistory("1234567890", null, null, null, 0, 20);

        verify(sellOrderMapper).selectSellOrderHistory("1234567890", null, null, null, 0, 20);
        verify(sellOrderMapper).countSellOrderHistory("1234567890", null, null, null);
    }

    @Test
    @DisplayName("status는 enum 이름 문자열로 변환해서 매퍼에 전달한다")
    void getSellOrderHistoryConvertsStatusEnumToNameForMapper() {
        when(sellOrderMapper.selectSellOrderHistory(null, "REJECTED", null, null, 0, 20))
                .thenReturn(List.of());
        when(sellOrderMapper.countSellOrderHistory(null, "REJECTED", null, null)).thenReturn(0);

        sellOrderService.getSellOrderHistory(null, SellOrderStatus.REJECTED, null, null, 0, 20);

        verify(sellOrderMapper).selectSellOrderHistory(null, "REJECTED", null, null, 0, 20);
        verify(sellOrderMapper).countSellOrderHistory(null, "REJECTED", null, null);
    }

    @Test
    @DisplayName("startDate/endDate는 각각 그 날의 00:00:00과 23:59:59로 변환해서 매퍼에 전달한다")
    void getSellOrderHistoryConvertsDateRangeToStartAndEndOfDayForMapper() {
        LocalDate startDate = LocalDate.of(2026, 8, 1);
        LocalDate endDate = LocalDate.of(2026, 8, 10);
        LocalDateTime expectedStart = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
        LocalDateTime expectedEnd = LocalDateTime.of(2026, 8, 10, 23, 59, 59);
        when(sellOrderMapper.selectSellOrderHistory(null, null, expectedStart, expectedEnd, 0, 20))
                .thenReturn(List.of());
        when(sellOrderMapper.countSellOrderHistory(null, null, expectedStart, expectedEnd))
                .thenReturn(0);

        sellOrderService.getSellOrderHistory(null, null, startDate, endDate, 0, 20);

        verify(sellOrderMapper)
                .selectSellOrderHistory(null, null, expectedStart, expectedEnd, 0, 20);
        verify(sellOrderMapper).countSellOrderHistory(null, null, expectedStart, expectedEnd);
    }

    @Test
    @DisplayName("매도 이력이 없으면 빈 목록과 0건을 담은 PageResponseDTO를 반환한다")
    void getSellOrderHistoryReturnsEmptyPageWhenNoOrdersExist() {
        when(sellOrderMapper.selectSellOrderHistory(null, null, null, null, 0, 20))
                .thenReturn(List.of());
        when(sellOrderMapper.countSellOrderHistory(null, null, null, null)).thenReturn(0);

        PageResponseDTO<SellOrderHistoryDTO> result =
                sellOrderService.getSellOrderHistory(null, null, null, null, 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalCount()).isZero();
    }

    @Test
    @DisplayName("오늘 매도금액/체결건수는 전일 대비 증감률과 함께 계산된다")
    void getSellOrderSummaryComputesChangeRatesAgainstYesterday() {
        LocalDate today = NOW.toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        when(businessClockService.now()).thenReturn(NOW);

        when(sellOrderMapper.sumSellAmountBetween(
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay()))
                .thenReturn(new BigDecimal("1100000"));
        when(sellOrderMapper.sumSellAmountBetween(yesterday.atStartOfDay(), today.atStartOfDay()))
                .thenReturn(new BigDecimal("1000000"));
        when(sellOrderMapper.countSellOrderHistory(
                        null, "EXECUTED", today.atStartOfDay(), today.atTime(23, 59, 59)))
                .thenReturn(5);
        when(sellOrderMapper.countSellOrderHistory(
                        null, "EXECUTED", yesterday.atStartOfDay(), yesterday.atTime(23, 59, 59)))
                .thenReturn(10);
        when(settlementService.getPendingProvisionalAmount()).thenReturn(new BigDecimal("2900000"));
        when(settlementService.getFinalizedAmountBetween(
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay()))
                .thenReturn(new BigDecimal("110000"));

        SellOrderSummaryDTO summary = sellOrderService.getSellOrderSummary();

        assertThat(summary.getTodaySellAmount()).isEqualByComparingTo("1100000");
        assertThat(summary.getTodaySellAmountChangeRate()).isEqualByComparingTo("10.0");
        assertThat(summary.getTodayExecutedCount()).isEqualTo(5);
        assertThat(summary.getTodayExecutedCountChangeRate()).isEqualByComparingTo("-50.0");
        assertThat(summary.getPendingProvisionalAmount()).isEqualByComparingTo("2900000");
        assertThat(summary.getTodayFinalizedAmount()).isEqualByComparingTo("110000");
    }

    @Test
    @DisplayName("전일 값이 0이면 증감률은 null이다")
    void getSellOrderSummaryReturnsNullChangeRateWhenYesterdayIsZero() {
        LocalDate today = NOW.toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        when(businessClockService.now()).thenReturn(NOW);

        when(sellOrderMapper.sumSellAmountBetween(
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay()))
                .thenReturn(new BigDecimal("500000"));
        when(sellOrderMapper.sumSellAmountBetween(yesterday.atStartOfDay(), today.atStartOfDay()))
                .thenReturn(BigDecimal.ZERO);
        when(sellOrderMapper.countSellOrderHistory(
                        null, "EXECUTED", today.atStartOfDay(), today.atTime(23, 59, 59)))
                .thenReturn(3);
        when(sellOrderMapper.countSellOrderHistory(
                        null, "EXECUTED", yesterday.atStartOfDay(), yesterday.atTime(23, 59, 59)))
                .thenReturn(0);
        when(settlementService.getPendingProvisionalAmount()).thenReturn(BigDecimal.ZERO);
        when(settlementService.getFinalizedAmountBetween(
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay()))
                .thenReturn(BigDecimal.ZERO);

        SellOrderSummaryDTO summary = sellOrderService.getSellOrderSummary();

        assertThat(summary.getTodaySellAmountChangeRate()).isNull();
        assertThat(summary.getTodayExecutedCountChangeRate()).isNull();
    }
}