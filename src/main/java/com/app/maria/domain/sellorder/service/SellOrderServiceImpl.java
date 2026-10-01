package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.dto.SellOrderDetailDTO;
import com.app.maria.domain.sellorder.dto.SellOrderHistoryDTO;
import com.app.maria.domain.sellorder.dto.SellOrderSummaryDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.mapper.SellOrderMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.domain.settlement.service.ProvisionalExchangeService;
import com.app.maria.domain.settlement.service.SettlementService;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.kis.KisExchangeCode;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class SellOrderServiceImpl implements SellOrderService {

    private final SellOrderMapper sellOrderMapper;
    private final KisPriceClient kis;
    private final ExchangeRateClient exchange;
    private final InboundMapper inboundMapper;
    private final ForeignProductMapper foreignProductMapper;
    private final SellLimitService sellLimitService;
    private final BusinessClockService businessClockService;
    private final ProvisionalExchangeService provisionalExchangeService;
    private final SettlementService settlementService;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<SellOrderResponseDTO> placeSellOrder(
            Long actorAdminId, SellOrderRequestDTO request) {

        SellOrderDTO sellOrderDTO = request.toSellOrderDTO();

        List<InboundDetailDTO> lots =
                inboundMapper.selectFifoLots(
                        sellOrderDTO.getAccountId(), sellOrderDTO.getForeignProductId());

        BigDecimal totalCurrentQty =
                lots.stream()
                        .map(InboundDetailDTO::getCurrentQty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (sellOrderDTO.getSellQty().compareTo(totalCurrentQty) > 0) {
            throw new AppException(ErrorType.SELL_ORDER_QTY_EXCEEDED);
        }

        ForeignProductDTO product =
                foreignProductMapper
                        .selectById(sellOrderDTO.getForeignProductId())
                        .orElseThrow(
                                () -> new ForeignProductNotFoundException("종목 정보를 찾을 수 없습니다."));

        String kisMarketCode = KisExchangeCode.fromMarket(product.getMarket());
        BigDecimal previousClose = kis.getPreviousClose(kisMarketCode, product.getTicker());

        BigDecimal exchangeRate = exchange.getBaseRate(product.getCurrency());
        sellOrderDTO.setBasePrice(previousClose.multiply(exchangeRate));
        sellOrderDTO.setSettlementFxRate(exchangeRate);

        BigDecimal orderAmount = sellOrderDTO.getSellQty().multiply(sellOrderDTO.getBasePrice());

        boolean withinLimit =
                sellLimitService.isWithinSellLimit(sellOrderDTO.getAccountId(), orderAmount);

        if (!withinLimit) {
            SellOrderDTO rejected =
                    sellOrderDTO.toBuilder()
                            .inboundDetailId(null)
                            .status(SellOrderStatus.REJECTED)
                            .processedAt(businessClockService.now())
                            .build();
            sellOrderMapper.insertSellOrder(rejected);

            return List.of(new SellOrderResponseDTO(rejected));
        }

        BigDecimal remainingQty = sellOrderDTO.getSellQty();
        List<SellOrderDTO> executeOrders = new ArrayList<>();

        for (InboundDetailDTO lot : lots) {
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal qtyFromThisLot = remainingQty.min(lot.getCurrentQty());

            int updateRows =
                    inboundMapper.decreaseCurrentQty(lot.getInboundDetailId(), qtyFromThisLot);
            if (updateRows == 0) {
                throw new AppException(ErrorType.SELL_ORDER_CONCURRENT_CONFLICT);
            }

            SellOrderDTO executed =
                    sellOrderDTO.toBuilder()
                            .inboundDetailId(lot.getInboundDetailId())
                            .sellQty(qtyFromThisLot)
                            .status(SellOrderStatus.EXECUTED)
                            .processedAt(businessClockService.now())
                            .build();
            sellOrderMapper.insertSellOrder(executed);
            executeOrders.add(executed);

            provisionalExchangeService.createProvisionalExchange(executed);

            remainingQty = remainingQty.subtract(qtyFromThisLot);
        }

        return executeOrders.stream().map(SellOrderResponseDTO::new).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SellOrderResponseDTO getSellOrder(Long orderId) {
        SellOrderDTO dto =
                sellOrderMapper
                        .selectSellOrderById(orderId)
                        .orElseThrow(() -> new AppException(ErrorType.SELL_ORDER_NOT_FOUND, orderId));
        return new SellOrderResponseDTO(dto);
    }

    @Override
    @Transactional(readOnly = true)
    public SellOrderDetailDTO getSellOrderDetail(Long orderId) {
        return sellOrderMapper
                .selectSellOrderDetail(orderId)
                .orElseThrow(() -> new AppException(ErrorType.SELL_ORDER_NOT_FOUND, orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellOrderResponseDTO> getSellOrderByAccount(Long accountId) {
        List<SellOrderDTO> orders = sellOrderMapper.selectSellOrdersByAccountId(accountId);
        return orders.stream().map(SellOrderResponseDTO::new).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTodaySellAmount() {
        LocalDateTime start = businessClockService.now().toLocalDate().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return sellOrderMapper.sumSellAmountBetween(start, end);
    }

    @Override
    @Transactional(readOnly = true)
    public SellOrderSummaryDTO getSellOrderSummary() {
        LocalDate today = businessClockService.now().toLocalDate();
        LocalDate yesterday = today.minusDays(1);

        BigDecimal todaySellAmount = getTodaySellAmount();
        BigDecimal yesterdaySellAmount =
                sellOrderMapper.sumSellAmountBetween(
                        yesterday.atStartOfDay(), today.atStartOfDay());

        int todayExecutedCount =
                sellOrderMapper.countSellOrderHistory(
                        null,
                        SellOrderStatus.EXECUTED.name(),
                        today.atStartOfDay(),
                        today.atTime(23, 59, 59));
        int yesterdayExecutedCount =
                sellOrderMapper.countSellOrderHistory(
                        null,
                        SellOrderStatus.EXECUTED.name(),
                        yesterday.atStartOfDay(),
                        yesterday.atTime(23, 59, 59));

        BigDecimal pendingProvisionalAmount = settlementService.getPendingProvisionalAmount();
        BigDecimal todayFinalizedAmount =
                settlementService.getFinalizedAmountBetween(
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay());

        return SellOrderSummaryDTO.builder()
                .todaySellAmount(todaySellAmount)
                .todaySellAmountChangeRate(changeRate(todaySellAmount, yesterdaySellAmount))
                .todayExecutedCount(todayExecutedCount)
                .todayExecutedCountChangeRate(
                        changeRate(
                                BigDecimal.valueOf(todayExecutedCount),
                                BigDecimal.valueOf(yesterdayExecutedCount)))
                .pendingProvisionalAmount(pendingProvisionalAmount)
                .todayFinalizedAmount(todayFinalizedAmount)
                .build();
    }

    private BigDecimal changeRate(BigDecimal today, BigDecimal yesterday) {
        if (yesterday == null || yesterday.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return today.subtract(yesterday)
                .divide(yesterday, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<SellOrderHistoryDTO> getSellOrderHistory(
            String keyword,
            SellOrderStatus status,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int size) {
        int offset = page * size;
        String statusName = status == null ? null : status.name();
        LocalDateTime startDateTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate == null ? null : endDate.atTime(23, 59, 59);

        List<SellOrderHistoryDTO> content =
                sellOrderMapper.selectSellOrderHistory(
                        keyword, statusName, startDateTime, endDateTime, offset, size);
        int totalCount =
                sellOrderMapper.countSellOrderHistory(
                        keyword, statusName, startDateTime, endDateTime);
        return PageResponseDTO.of(content, totalCount, page, size);
    }
}
