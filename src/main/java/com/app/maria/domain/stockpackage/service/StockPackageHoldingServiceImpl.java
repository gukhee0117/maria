package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResultDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageHoldingResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.service.StockPackageHoldingCalculator.Position;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 읽기 전용. 현재가(KIS) 조회가 느릴 수 있어 DB 트랜잭션을 잡지 않는다. */
@Service
@RequiredArgsConstructor
public class StockPackageHoldingServiceImpl implements StockPackageHoldingService {
    static final int ORDER_LIST_LIMIT = 200;
    private static final int QUOTE_CHUNK = 20;
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final StockPackageMapper stockPackageMapper;
    private final StockOrderMapper stockOrderMapper;
    private final AccountMapper accountMapper;
    private final StockQuoteService stockQuoteService;

    @Override
    public StockPackageHoldingResponseDTO getHoldings(Long customerId, Long stockPackageId) {
        requireOwned(customerId, stockPackageId);
        List<StockPackageItemVO> pkgItems =
                stockPackageMapper.selectItemsByPackageId(stockPackageId);
        List<Position> positions =
                StockPackageHoldingCalculator.positions(
                        stockOrderMapper.selectExecutedByPackageAsc(stockPackageId));
        Map<String, StockQuoteVO> quotes = quotesFor(tickers(pkgItems, positions));
        return assemble(stockPackageId, pkgItems, positions, quotes, accountFigures(customerId));
    }

    @Override
    public List<StockPackageHoldingResponseDTO> getAllHoldings(Long customerId) {
        List<StockPackageVO> packages = stockPackageMapper.selectSummariesByCustomerId(customerId);
        if (packages.isEmpty()) {
            return List.of();
        }
        Map<Long, List<StockOrderVO>> ordersByPackage = new HashMap<>();
        for (StockOrderVO o : stockOrderMapper.selectExecutedByCustomerAsc(customerId)) {
            ordersByPackage.computeIfAbsent(o.getStockPackageId(), k -> new ArrayList<>()).add(o);
        }
        Map<Long, List<StockPackageItemVO>> itemsByPackage = new HashMap<>();
        Map<Long, List<Position>> positionsByPackage = new HashMap<>();
        List<String> allTickers = new ArrayList<>();
        for (StockPackageVO p : packages) {
            List<StockPackageItemVO> items =
                    stockPackageMapper.selectItemsByPackageId(p.getStockPackageId());
            List<Position> positions =
                    StockPackageHoldingCalculator.positions(
                            ordersByPackage.getOrDefault(p.getStockPackageId(), List.of()));
            itemsByPackage.put(p.getStockPackageId(), items);
            positionsByPackage.put(p.getStockPackageId(), positions);
            allTickers.addAll(tickers(items, positions));
        }
        Map<String, StockQuoteVO> quotes = quotesFor(allTickers);
        AccountFigures account = accountFigures(customerId);
        return packages.stream()
                .map(
                        p ->
                                assemble(
                                        p.getStockPackageId(),
                                        itemsByPackage.get(p.getStockPackageId()),
                                        positionsByPackage.get(p.getStockPackageId()),
                                        quotes,
                                        account))
                .toList();
    }

    @Override
    public List<StockOrderResultDTO> getOrders(Long customerId, Long stockPackageId) {
        if (stockPackageId != null) {
            requireOwned(customerId, stockPackageId);
        }
        return stockOrderMapper
                .selectOrdersByCustomer(customerId, stockPackageId, ORDER_LIST_LIMIT)
                .stream()
                .map(StockOrderResultDTO::new)
                .toList();
    }

    private void requireOwned(Long customerId, Long stockPackageId) {
        stockPackageMapper
                .selectByIdAndCustomerId(stockPackageId, customerId)
                .orElseThrow(() -> new AppException(ErrorType.STOCK_PACKAGE_NOT_FOUND));
    }

    private static List<String> tickers(List<StockPackageItemVO> items, List<Position> positions) {
        Set<String> set = new LinkedHashSet<>();
        items.forEach(i -> set.add(i.getTicker()));
        positions.forEach(p -> set.add(p.ticker()));
        return List.copyOf(set);
    }

    /** 20종목씩 끊어 현재가를 조회한다(한 번에 받을 수 있는 최대 개수). */
    private Map<String, StockQuoteVO> quotesFor(Collection<String> tickers) {
        List<String> unique = tickers.stream().distinct().toList();
        Map<String, StockQuoteVO> quotes = new HashMap<>();
        for (int i = 0; i < unique.size(); i += QUOTE_CHUNK) {
            stockQuoteService
                    .findQuotes(unique.subList(i, Math.min(unique.size(), i + QUOTE_CHUNK)))
                    .forEach(q -> quotes.put(q.getTicker(), q));
        }
        return quotes;
    }

    private record AccountFigures(BigDecimal amount, BigDecimal buyingPower) {}

    private AccountFigures accountFigures(Long customerId) {
        Optional<AccountDTO> account = accountMapper.selectByCustomerId(customerId);
        if (account.isEmpty()) {
            return new AccountFigures(null, null);
        }
        AccountDTO a = account.get();
        return new AccountFigures(
                a.getAmount(),
                a.getAmount().subtract(stockOrderMapper.sumProvisionalAmount(a.getAccountId())));
    }

    private StockPackageHoldingResponseDTO assemble(
            Long stockPackageId,
            List<StockPackageItemVO> pkgItems,
            List<Position> positions,
            Map<String, StockQuoteVO> quotes,
            AccountFigures account) {
        // 담은 종목은 모두 보여주고(안 샀으면 0주), 담은 뒤 목록에서 빠졌어도 아직 보유한 종목은 뒤에 붙인다
        Map<Long, Position> held = new LinkedHashMap<>();
        positions.forEach(p -> held.put(p.domesticProductId(), p));
        List<Position> rows = new ArrayList<>();
        for (StockPackageItemVO i : pkgItems) {
            Position p = held.remove(i.getDomesticProductId());
            rows.add(
                    p != null
                            ? p
                            : new Position(
                                    i.getDomesticProductId(),
                                    i.getTicker(),
                                    i.getName(),
                                    StockOrderCalculator.isTaxable(i.getType(), i.getMarket()),
                                    BigDecimal.ZERO,
                                    BigDecimal.ZERO));
        }
        rows.addAll(held.values());

        List<StockPackageHoldingResponseDTO.Item> items = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalEval = BigDecimal.ZERO;
        boolean allHeldPriced = true;
        for (Position p : rows) {
            boolean holding = p.qty().signum() > 0;
            BigDecimal cost = StockOrderCalculator.cost(p.qty(), p.avgPrice());
            totalCost = totalCost.add(cost);
            StockQuoteVO q = quotes.get(p.ticker());
            if (q == null || !q.isAvailable()) {
                allHeldPriced &= !holding;
                items.add(item(p, cost, null, null, null, null, null, null));
                continue;
            }
            BigDecimal eval = StockOrderCalculator.cost(p.qty(), q.getPrice());
            totalEval = totalEval.add(eval);
            BigDecimal profit = eval.subtract(cost);
            items.add(
                    item(
                            p,
                            cost,
                            q.getPrice(),
                            q.getPreviousClose(),
                            rate(q.getPrice().subtract(q.getPreviousClose()), q.getPreviousClose()),
                            eval,
                            profit,
                            rate(profit, cost)));
        }
        return new StockPackageHoldingResponseDTO(
                stockPackageId,
                items,
                totalCost,
                allHeldPriced ? totalEval : null,
                allHeldPriced ? totalEval.subtract(totalCost) : null,
                account.amount(),
                account.buyingPower());
    }

    private static StockPackageHoldingResponseDTO.Item item(
            Position p,
            BigDecimal cost,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal changePercent,
            BigDecimal eval,
            BigDecimal profit,
            BigDecimal rate) {
        return new StockPackageHoldingResponseDTO.Item(
                p.domesticProductId(),
                p.ticker(),
                p.name(),
                p.taxable(),
                p.qty(),
                p.avgPrice(),
                cost,
                price,
                previousClose,
                changePercent,
                eval,
                profit,
                rate);
    }

    private static BigDecimal rate(BigDecimal part, BigDecimal base) {
        return base == null || base.signum() == 0
                ? null
                : part.multiply(PERCENT).divide(base, 2, RoundingMode.HALF_UP);
    }
}
