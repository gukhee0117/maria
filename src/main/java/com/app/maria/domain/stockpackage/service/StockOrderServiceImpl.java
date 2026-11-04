package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticOrderStatus;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.stockpackage.dto.request.StockOrderRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderFeeResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResultDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.type.StockOrderRejectReason;
import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 순서: ① 요청·패키지·계좌 확인 → ② KIS 현재가 조회(락 밖) → ③ 한 트랜잭션에서 계좌 행을 잠그고 종목마다 체결 또는 거부 기록. 현재가 조회처럼 느린 외부
 * 호출을 계좌 락 안에서 하지 않으려고 ②를 먼저 한다. 증권사 시스템(domestic_trade)에는 아직 체결을 보내지 않아 securities_trade_id는 비워
 * 둔다.
 */
@Service
public class StockOrderServiceImpl implements StockOrderService {
    private static final String CHANNEL_CUSTOMER = "CUSTOMER";
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final StockPackageMapper stockPackageMapper;
    private final StockOrderMapper stockOrderMapper;
    private final AccountMapper accountMapper;
    private final DomesticProductMapper domesticProductMapper;
    private final TaxMapper taxMapper;
    private final StockQuoteService stockQuoteService;
    private final DomesticPurchaseEligibilityService eligibilityService;
    private final BusinessClockService businessClockService;
    private final boolean enforceMarketHours;
    private final TransactionTemplate tx;

    public StockOrderServiceImpl(
            StockPackageMapper stockPackageMapper,
            StockOrderMapper stockOrderMapper,
            AccountMapper accountMapper,
            DomesticProductMapper domesticProductMapper,
            TaxMapper taxMapper,
            StockQuoteService stockQuoteService,
            DomesticPurchaseEligibilityService eligibilityService,
            BusinessClockService businessClockService,
            PlatformTransactionManager transactionManager,
            @Value("${maria.stock-order.enforce-market-hours:false}") boolean enforceMarketHours) {
        this.stockPackageMapper = stockPackageMapper;
        this.stockOrderMapper = stockOrderMapper;
        this.accountMapper = accountMapper;
        this.domesticProductMapper = domesticProductMapper;
        this.taxMapper = taxMapper;
        this.stockQuoteService = stockQuoteService;
        this.eligibilityService = eligibilityService;
        this.businessClockService = businessClockService;
        this.enforceMarketHours = enforceMarketHours;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public StockOrderResponseDTO place(
            Long customerId, Long stockPackageId, StockOrderRequestDTO request) {
        requireWholeShares(request);
        stockPackageMapper
                .selectByIdAndCustomerId(stockPackageId, customerId)
                .orElseThrow(() -> new AppException(ErrorType.STOCK_PACKAGE_NOT_FOUND));
        Set<Long> packageProductIds =
                stockPackageMapper.selectItemsByPackageId(stockPackageId).stream()
                        .map(StockPackageItemVO::getDomesticProductId)
                        .collect(Collectors.toSet());
        for (StockOrderRequestDTO.Item item : request.items()) {
            if (!packageProductIds.contains(item.domesticProductId())) {
                throw new AppException(ErrorType.STOCK_ORDER_ITEM_NOT_IN_PACKAGE);
            }
        }
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        if (!account.getStatus().canTrade()) {
            throw new AppException(ErrorType.STOCK_ORDER_ACCOUNT_NOT_OPEN);
        }
        requireMarketOpen(businessClockService.now());

        Map<Long, DomesticProductDTO> products = new HashMap<>();
        for (StockOrderRequestDTO.Item item : request.items()) {
            products.computeIfAbsent(
                    item.domesticProductId(),
                    id ->
                            domesticProductMapper
                                    .selectById(id)
                                    .orElseThrow(
                                            () ->
                                                    new AppException(
                                                            ErrorType.DOMESTIC_PRODUCT_NOT_FOUND,
                                                            id)));
        }
        Map<String, StockQuoteVO> quotes =
                stockQuoteService
                        .findQuotes(
                                products.values().stream()
                                        .map(DomesticProductDTO::getTicker)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(StockQuoteVO::getTicker, q -> q));

        return tx.execute(
                status ->
                        execute(account.getAccountId(), stockPackageId, request, products, quotes));
    }

    @Override
    public StockOrderFeeResponseDTO getFees(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        LocalDate today = businessClockService.now().toLocalDate();
        List<TaxRuleDTO> rules = taxMapper.selectTaxRules();
        return new StockOrderFeeResponseDTO(
                StockOrderCalculator.commissionRatePercent(
                        account.getOpenedAt().toLocalDate(), today, rules),
                StockOrderCalculator.securitiesTaxRatePercent(today, rules));
    }

    private StockOrderResponseDTO execute(
            Long accountId,
            Long stockPackageId,
            StockOrderRequestDTO request,
            Map<Long, DomesticProductDTO> products,
            Map<String, StockQuoteVO> quotes) {
        // 같은 계좌의 주문은 이 락으로 한 줄로 선다(동시에 두 번 눌러도 예수금이 마이너스가 되지 않는다)
        AccountDTO account =
                accountMapper
                        .selectByAccountIdForUpdate(accountId)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        if (!account.getStatus().canTrade()) {
            throw new AppException(ErrorType.STOCK_ORDER_ACCOUNT_NOT_OPEN);
        }
        LocalDateTime now = businessClockService.now();
        List<TaxRuleDTO> rules = taxMapper.selectTaxRules();
        BigDecimal amountNow = account.getAmount();
        BigDecimal buyingPower =
                amountNow.subtract(stockOrderMapper.sumProvisionalAmount(accountId));

        List<StockOrderResultDTO> results = new ArrayList<>();
        int index = 0;
        for (StockOrderRequestDTO.Item item : request.items()) {
            index++;
            String key = request.idempotencyKey() + ":" + index;
            Optional<StockOrderVO> prior = stockOrderMapper.selectByIdempotencyKey(accountId, key);
            if (prior.isPresent()) {
                results.add(new StockOrderResultDTO(prior.get()));
                continue;
            }
            DomesticProductDTO product = products.get(item.domesticProductId());
            StockOrderVO order =
                    process(
                            account,
                            stockPackageId,
                            key,
                            item,
                            product,
                            quotes.get(product.getTicker()),
                            buyingPower,
                            rules,
                            now);
            if (order.getStatus() == DomesticOrderStatus.EXECUTED) {
                amountNow = amountNow.add(order.getAmount());
                buyingPower = buyingPower.add(order.getAmount());
            }
            results.add(new StockOrderResultDTO(order));
        }
        return new StockOrderResponseDTO(results, amountNow, buyingPower);
    }

    private StockOrderVO process(
            AccountDTO account,
            Long stockPackageId,
            String key,
            StockOrderRequestDTO.Item item,
            DomesticProductDTO product,
            StockQuoteVO quote,
            BigDecimal buyingPower,
            List<TaxRuleDTO> rules,
            LocalDateTime now) {
        Long accountId = account.getAccountId();
        DomesticOrderSide side = item.side();
        BigDecimal qty = item.qty();
        StockBalanceVO balance =
                stockOrderMapper
                        .selectBalanceForUpdate(accountId, product.getDomesticProductId())
                        .orElse(null);

        StockOrderRejectReason reason = null;
        BigDecimal price = null;
        BigDecimal cost = null;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        if (side == DomesticOrderSide.BUY
                && !eligibilityService.isPurchasable(
                        product.getType(),
                        product.getDomesticStockRatio(),
                        product.getInceptionDate())) {
            reason = StockOrderRejectReason.NOT_ELIGIBLE;
        } else if (quote == null || !quote.isAvailable()) {
            reason = StockOrderRejectReason.PRICE_UNAVAILABLE;
        } else {
            LocalDate today = now.toLocalDate();
            price = quote.getPrice();
            cost = StockOrderCalculator.cost(qty, price);
            fee =
                    StockOrderCalculator.commission(
                            cost, account.getOpenedAt().toLocalDate(), today, rules);
            tax =
                    StockOrderCalculator.securitiesTax(
                            side, product.getType(), product.getMarket(), cost, today, rules);
            if (side == DomesticOrderSide.BUY) {
                if (balance != null && StockOrderCalculator.isBlocked(balance.getStatus())) {
                    reason = StockOrderRejectReason.TRADE_RESTRICTED;
                } else if (cost.add(fee).compareTo(buyingPower) > 0) {
                    reason = StockOrderRejectReason.INSUFFICIENT_CASH;
                }
            } else if (balance == null || balance.getQty().compareTo(qty) < 0) {
                reason = StockOrderRejectReason.INSUFFICIENT_QTY;
            } else if (StockOrderCalculator.isBlocked(balance.getStatus())) {
                reason = StockOrderRejectReason.TRADE_RESTRICTED;
            }
        }

        StockOrderVO.StockOrderVOBuilder order =
                StockOrderVO.builder()
                        .accountId(accountId)
                        .domesticProductId(product.getDomesticProductId())
                        .stockPackageId(stockPackageId)
                        .side(side)
                        .qty(qty)
                        .channel(CHANNEL_CUSTOMER)
                        .idempotencyKey(key)
                        .processedAt(now)
                        .ticker(product.getTicker())
                        .name(product.getName());
        if (reason != null) {
            StockOrderVO rejected =
                    order.status(DomesticOrderStatus.REJECTED)
                            .rejectReason(reason)
                            .feeAmount(BigDecimal.ZERO)
                            .taxAmount(BigDecimal.ZERO)
                            .build();
            stockOrderMapper.insertOrder(rejected);
            return rejected;
        }

        BigDecimal net = StockOrderCalculator.netAmount(side, cost, fee, tax);
        applyToBalance(accountId, product.getDomesticProductId(), balance, side, qty, price, now);
        StockOrderVO executed =
                order.status(DomesticOrderStatus.EXECUTED)
                        .price(price)
                        .amount(net)
                        .feeAmount(fee)
                        .taxAmount(tax)
                        .build();
        stockOrderMapper.insertOrder(executed);
        // 예수금은 방금 기록한 체결 주문의 금액으로만 바꾼다(호출하는 쪽이 금액을 넘기지 않는다)
        stockOrderMapper.applyOrderAmount(accountId, executed.getDomesticOrderId());
        return executed;
    }

    private void applyToBalance(
            Long accountId,
            Long productId,
            StockBalanceVO balance,
            DomesticOrderSide side,
            BigDecimal qty,
            BigDecimal price,
            LocalDateTime now) {
        if (side == DomesticOrderSide.BUY) {
            if (balance == null) {
                stockOrderMapper.insertBalance(
                        StockBalanceVO.builder()
                                .accountId(accountId)
                                .domesticProductId(productId)
                                .qty(qty)
                                .status(DomesticStockStatus.HOLDING)
                                .lastPurchaseDate(now)
                                .avgPurchasePrice(price)
                                .build());
                return;
            }
            balance.setAvgPurchasePrice(
                    StockOrderCalculator.averageAfterBuy(
                            balance.getQty(), balance.getAvgPurchasePrice(), qty, price));
            balance.setQty(balance.getQty().add(qty));
            balance.setStatus(DomesticStockStatus.HOLDING);
            balance.setLastPurchaseDate(now);
            stockOrderMapper.updateBalance(balance);
            return;
        }
        BigDecimal remaining = balance.getQty().subtract(qty);
        balance.setQty(remaining);
        balance.setStatus(StockOrderCalculator.statusAfterSell(remaining));
        stockOrderMapper.updateBalance(balance);
    }

    private static void requireWholeShares(StockOrderRequestDTO request) {
        for (StockOrderRequestDTO.Item item : request.items()) {
            // 정수 주만 가능(domestic_order CHECK와 같은 규칙). 3.5주·0.0001주는 거부
            if (item.qty().stripTrailingZeros().scale() > 0) {
                throw new AppException(ErrorType.STOCK_ORDER_REQUEST_INVALID);
            }
        }
    }

    private void requireMarketOpen(LocalDateTime now) {
        if (!enforceMarketHours) {
            return;
        }
        DayOfWeek day = now.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        LocalTime time = now.toLocalTime();
        if (weekend || time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
            throw new AppException(ErrorType.STOCK_ORDER_MARKET_CLOSED);
        }
    }
}
