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
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 순서: ① 요청·패키지·계좌 확인 → ② KIS 현재가 조회(락 밖) → ③ 한 트랜잭션에서 계좌 행을 잠그고 종목마다 체결 또는 거부 기록. 현재가 조회처럼 느린 외부
 * 호출을 계좌 락 안에서 하지 않으려고 ②를 먼저 한다. 증권사 시스템(domestic_trade)에는 아직 체결을 보내지 않아 securities_trade_id는 비워
 * 둔다.
 */
@Service
public class StockOrderServiceImpl implements StockOrderService {
    private static final String CHANNEL_CUSTOMER = "CUSTOMER";
    private static final String CHANNEL_GENERATOR = "GENERATOR";
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
    private final SecuritiesOrderRecorder recorder;
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
            SecuritiesOrderRecorder recorder,
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
        this.recorder = recorder;
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
        // 패키지에서 뺀 종목도 이 패키지로 이미 산 적이 있으면 팔 수 있어야 한다(매수는 소속 종목만)
        Set<Long> sellableProductIds = new HashSet<>(packageProductIds);
        if (request.items().stream().anyMatch(i -> i.side() == DomesticOrderSide.SELL)) {
            stockOrderMapper.selectExecutedByPackageAsc(stockPackageId).stream()
                    .map(StockOrderVO::getDomesticProductId)
                    .forEach(sellableProductIds::add);
        }
        for (StockOrderRequestDTO.Item item : request.items()) {
            Set<Long> allowed =
                    item.side() == DomesticOrderSide.SELL ? sellableProductIds : packageProductIds;
            if (!allowed.contains(item.domesticProductId())) {
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
                        execute(
                                account.getAccountId(),
                                stockPackageId,
                                request,
                                products,
                                quotes,
                                CHANNEL_CUSTOMER));
    }

    /**
     * 증권사 거래 생성기의 주문 한 건. 패키지 주문과 같은 {@code execute}(계좌 락·멱등·주문 가능 금액)를 채널만 바꿔 쓴다. 저장되는 멱등키는 {@code
     * idempotencyKey + ":1"}이다.
     */
    @Override
    public StockOrderResultDTO placeGeneratorOrder(
            Long accountId,
            DomesticProductDTO product,
            StockQuoteVO quote,
            DomesticOrderSide side,
            int qty,
            String idempotencyKey) {
        StockOrderRequestDTO request =
                new StockOrderRequestDTO(
                        idempotencyKey,
                        List.of(
                                new StockOrderRequestDTO.Item(
                                        product.getDomesticProductId(),
                                        side,
                                        BigDecimal.valueOf(qty))));
        return tx.execute(
                        status ->
                                execute(
                                        accountId,
                                        null,
                                        request,
                                        Map.of(product.getDomesticProductId(), product),
                                        Map.of(product.getTicker(), quote),
                                        CHANNEL_GENERATOR))
                .orders()
                .get(0);
    }

    @Override
    public Optional<StockOrderResultDTO> findGeneratorOrder(Long accountId, String idempotencyKey) {
        return stockOrderMapper
                .selectByIdempotencyKey(accountId, idempotencyKey + ":1")
                .map(StockOrderResultDTO::new);
    }

    @Override
    public StockOrderFeeResponseDTO getFees(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        if (!account.getStatus().canTrade() || account.getOpenedAt() == null) {
            throw new AppException(ErrorType.STOCK_ORDER_ACCOUNT_NOT_OPEN);
        }
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
            Map<String, StockQuoteVO> quotes,
            String channel) {
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

        // 이 패키지로 체결된 주문. 계좌 락 안에서 읽으므로 같은 패키지의 동시 주문도 한 줄로 선다. 생성기 주문이면 null
        List<StockOrderVO> packageOrders =
                stockPackageId == null
                        ? null
                        : stockOrderMapper.selectExecutedByPackageAsc(stockPackageId);
        // 패키지 주문의 종목별 매수 가능 잔여 금액(패키지 총액 x 목표 비중 - 이미 담은 금액). 총액이 없거나 생성기 주문이면 null(제한 없음)
        Map<Long, BigDecimal> weightRoom =
                stockPackageId == null
                        ? null
                        : weightRoomByProduct(
                                account.getCustomerId(), stockPackageId, packageOrders);
        // 패키지 주문의 종목별 매도 가능 수량(이 패키지의 보유). 계좌 잔고만 보면 다른 패키지·생성기 몫까지 팔려 보유가 음수가 된다. 생성기 주문이면 null
        Map<Long, BigDecimal> packageQty =
                packageOrders == null ? null : heldQtyByProduct(packageOrders);

        List<StockOrderResultDTO> results = new ArrayList<>();
        List<StockOrderVO> toRecord = new ArrayList<>();
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
                            now,
                            channel,
                            weightRoom,
                            packageQty);
            if (order.getStatus() == DomesticOrderStatus.EXECUTED) {
                if (packageQty != null) {
                    packageQty.merge(
                            order.getDomesticProductId(),
                            order.getSide() == DomesticOrderSide.BUY
                                    ? order.getQty()
                                    : order.getQty().negate(),
                            BigDecimal::add);
                }
                if (weightRoom != null) {
                    BigDecimal executedCost =
                            StockOrderCalculator.cost(order.getQty(), order.getPrice());
                    // 패키지에 없는 종목(제거 후 매도)은 한도 맵에 새로 만들지 않는다
                    weightRoom.computeIfPresent(
                            order.getDomesticProductId(),
                            (id, left) ->
                                    order.getSide() == DomesticOrderSide.BUY
                                            ? left.subtract(executedCost)
                                            : left.add(executedCost));
                }
                if (CHANNEL_CUSTOMER.equals(channel)) {
                    toRecord.add(order);
                }
                amountNow = amountNow.add(order.getAmount());
                buyingPower = buyingPower.add(order.getAmount());
            }
            results.add(new StockOrderResultDTO(order));
        }
        recordInSecuritiesAfterCommit(account, toRecord);
        return new StockOrderResponseDTO(results, amountNow, buyingPower);
    }

    /**
     * 고객 주문의 체결을 증권사 거래로 남긴다. 주문 트랜잭션이 커밋된 뒤에 보내므로 커밋이 실패한 주문이 증권사에만 남지 않는다. 보내지 못하면 주문은 그대로 두고
     * ({@code securities_recorded_at}이 비어 있는 채로) {@link SecuritiesOrderRecorder}의 재시도가 이어서 보낸다. 거래
     * 생성기 주문은 증권사가 먼저 시작한 것이라 대상이 아니다.
     */
    private void recordInSecuritiesAfterCommit(AccountDTO account, List<StockOrderVO> orders) {
        if (orders.isEmpty()) {
            return;
        }
        String ciHash =
                accountMapper.selectCiHashByCustomerId(account.getCustomerId()).orElse(null);
        if (ciHash == null) {
            return;
        }
        Runnable send = () -> orders.forEach(order -> recorder.record(ciHash, order));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send.run();
                        }
                    });
        } else {
            send.run();
        }
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
            LocalDateTime now,
            String channel,
            Map<Long, BigDecimal> weightRoom,
            Map<Long, BigDecimal> packageQty) {
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
                } else if (weightRoom != null
                        && cost.compareTo(
                                        weightRoom.getOrDefault(
                                                product.getDomesticProductId(), BigDecimal.ZERO))
                                > 0) {
                    // 비중 한도는 체결금액(cost)만 본다. 이미 담은 금액도 수수료를 뺀 cost로 차감하므로 한도 계산은 일관된다.
                    // 한도 직전까지 담아 수수료 낼 현금이 모자라면 아래 예수금 검사에서 INSUFFICIENT_CASH가 된다(의도한 순서)
                    reason = StockOrderRejectReason.WEIGHT_LIMIT_EXCEEDED;
                } else if (cost.add(fee).compareTo(buyingPower) > 0) {
                    reason = StockOrderRejectReason.INSUFFICIENT_CASH;
                }
            } else if (balance == null
                    || balance.getQty().compareTo(qty) < 0
                    || (packageQty != null
                            && packageQty
                                            .getOrDefault(
                                                    product.getDomesticProductId(), BigDecimal.ZERO)
                                            .compareTo(qty)
                                    < 0)) {
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
                        .channel(channel)
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

    /**
     * 패키지 종목별로 더 살 수 있는 금액. 패키지 총액 x 목표 비중(%)에서 이 패키지로 이미 산 금액(매수 - 매도, 수량 x 체결가)을 뺀다. 패키지 총액이 없으면
     * 비중의 기준 금액이 없어 null을 돌려준다(제한하지 않음). 패키지가 없으면(주문 도중 삭제 등) 한도 없이 체결하지 않고
     * STOCK_PACKAGE_NOT_FOUND로 막는다.
     */
    private Map<Long, BigDecimal> weightRoomByProduct(
            Long customerId, Long stockPackageId, List<StockOrderVO> packageOrders) {
        StockPackageVO pkg =
                stockPackageMapper
                        .selectByIdAndCustomerId(stockPackageId, customerId)
                        .orElseThrow(() -> new AppException(ErrorType.STOCK_PACKAGE_NOT_FOUND));
        if (pkg.getTotalAmount() == null) {
            return null;
        }
        Map<Long, BigDecimal> room = new HashMap<>();
        for (StockPackageItemVO packageItem :
                stockPackageMapper.selectItemsByPackageId(stockPackageId)) {
            BigDecimal limit =
                    pkg.getTotalAmount()
                            .multiply(packageItem.getWeight())
                            .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN);
            room.put(packageItem.getDomesticProductId(), limit);
        }
        for (StockOrderVO executed : packageOrders) {
            BigDecimal cost = StockOrderCalculator.cost(executed.getQty(), executed.getPrice());
            room.computeIfPresent(
                    executed.getDomesticProductId(),
                    (id, left) ->
                            executed.getSide() == DomesticOrderSide.BUY
                                    ? left.subtract(cost)
                                    : left.add(cost));
        }
        return room;
    }

    /** 패키지 보유 화면과 같은 계산(체결 매수 - 매도)으로 구한 종목별 보유 수량. */
    private static Map<Long, BigDecimal> heldQtyByProduct(List<StockOrderVO> packageOrders) {
        Map<Long, BigDecimal> held = new HashMap<>();
        for (StockPackageHoldingCalculator.Position p :
                StockPackageHoldingCalculator.positions(packageOrders)) {
            held.put(p.domesticProductId(), p.qty());
        }
        return held;
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
