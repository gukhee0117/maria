package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.dto.response.GeneratorOrderResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResultDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 종목·수량은 여기서 정한다(증권사 생성기는 고객과 매수/매도만 보낸다). 매수는 누군가 들고 있는 상품 중 RIA에서 살 수 있는 것, 매도는 그 계좌가 들고 있는 종목
 * 중에서 고른다. 수량은 1~25주 무작위이고, 매수는 수수료까지 합쳐 살 수 있는 만큼으로 줄인다. 계좌 락·멱등은 {@link
 * StockOrderService#placeGeneratorOrder}가 맡는다.
 */
@Service
public class GeneratorOrderServiceImpl implements GeneratorOrderService {
    private static final int MAX_QTY = 25;
    private static final Duration CANDIDATE_TTL = Duration.ofMinutes(1);

    private final AccountMapper accountMapper;
    private final StockOrderMapper orderMapper;
    private final DomesticProductMapper productMapper;
    private final TaxMapper taxMapper;
    private final StockQuoteService quoteService;
    private final DomesticPurchaseEligibilityService eligibility;
    private final BusinessClockService businessClock;
    private final StockOrderService orderService;
    private final Clock clock;
    private final Set<String> excludedCiHashes;
    private final Random random;

    private volatile List<Long> heldProductIds = List.of();
    private volatile Instant heldLoadedAt = Instant.MIN;

    @Autowired
    public GeneratorOrderServiceImpl(
            AccountMapper accountMapper,
            StockOrderMapper orderMapper,
            DomesticProductMapper productMapper,
            TaxMapper taxMapper,
            StockQuoteService quoteService,
            DomesticPurchaseEligibilityService eligibility,
            BusinessClockService businessClock,
            StockOrderService orderService,
            Clock clock,
            @Value("${custom.internal.excluded-ci-hashes:}") Set<String> excludedCiHashes) {
        this(
                accountMapper,
                orderMapper,
                productMapper,
                taxMapper,
                quoteService,
                eligibility,
                businessClock,
                orderService,
                clock,
                excludedCiHashes,
                new Random());
    }

    GeneratorOrderServiceImpl(
            AccountMapper accountMapper,
            StockOrderMapper orderMapper,
            DomesticProductMapper productMapper,
            TaxMapper taxMapper,
            StockQuoteService quoteService,
            DomesticPurchaseEligibilityService eligibility,
            BusinessClockService businessClock,
            StockOrderService orderService,
            Clock clock,
            Set<String> excludedCiHashes,
            Random random) {
        this.accountMapper = accountMapper;
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
        this.taxMapper = taxMapper;
        this.quoteService = quoteService;
        this.eligibility = eligibility;
        this.businessClock = businessClock;
        this.orderService = orderService;
        this.clock = clock;
        this.excludedCiHashes = excludedCiHashes;
        this.random = random;
    }

    @Override
    public GeneratorOrderResponseDTO order(
            String ciHash, DomesticOrderSide side, String idempotencyKey) {
        if (excludedCiHashes.contains(ciHash)) {
            return GeneratorOrderResponseDTO.rejected("EXCLUDED_CUSTOMER");
        }
        AccountDTO account = accountMapper.selectByCiHash(ciHash).orElse(null);
        if (account == null || !account.getStatus().canTrade()) {
            return GeneratorOrderResponseDTO.rejected("NO_ACCOUNT");
        }
        Optional<StockOrderResultDTO> prior =
                orderService.findGeneratorOrder(account.getAccountId(), idempotencyKey);
        if (prior.isPresent()) {
            return GeneratorOrderResponseDTO.from(prior.get());
        }
        return side == DomesticOrderSide.BUY
                ? buy(account, idempotencyKey)
                : sell(account, idempotencyKey);
    }

    private GeneratorOrderResponseDTO buy(AccountDTO account, String key) {
        Optional<DomesticProductDTO> product = pickPurchasableProduct();
        if (product.isEmpty()) {
            return GeneratorOrderResponseDTO.rejected("NOT_ELIGIBLE");
        }
        StockQuoteVO quote = quoteOf(product.get());
        int wanted = 1 + random.nextInt(MAX_QTY);
        int qty = quote.isAvailable() ? affordableQty(account, quote.getPrice(), wanted) : wanted;
        if (qty == 0) {
            return GeneratorOrderResponseDTO.rejected("INSUFFICIENT_CASH");
        }
        return GeneratorOrderResponseDTO.from(
                orderService.placeGeneratorOrder(
                        account.getAccountId(),
                        product.get(),
                        quote,
                        DomesticOrderSide.BUY,
                        qty,
                        key));
    }

    private GeneratorOrderResponseDTO sell(AccountDTO account, String key) {
        List<StockBalanceVO> sellable =
                orderMapper.selectHeldBalancesByAccount(account.getAccountId()).stream()
                        .filter(b -> !StockOrderCalculator.isBlocked(b.getStatus()))
                        .toList();
        Optional<DomesticProductDTO> product = Optional.empty();
        StockBalanceVO balance = null;
        if (!sellable.isEmpty()) {
            balance = sellable.get(random.nextInt(sellable.size()));
            product = productMapper.selectById(balance.getDomesticProductId());
        }
        if (product.isEmpty()) {
            return GeneratorOrderResponseDTO.rejected("NO_HOLDING");
        }
        int qty = Math.min(1 + random.nextInt(MAX_QTY), balance.getQty().intValue());
        return GeneratorOrderResponseDTO.from(
                orderService.placeGeneratorOrder(
                        account.getAccountId(),
                        product.get(),
                        quoteOf(product.get()),
                        DomesticOrderSide.SELL,
                        qty,
                        key));
    }

    /** 목록은 1분 캐시하고, 뽑힌 상품만 그때 다시 살 수 있는지 판정한다. */
    private Optional<DomesticProductDTO> pickPurchasableProduct() {
        List<Long> candidates = new ArrayList<>(heldProducts());
        Collections.shuffle(candidates, random);
        for (Long productId : candidates) {
            if (eligibility.isPurchasable(productId)) {
                Optional<DomesticProductDTO> product = productMapper.selectById(productId);
                if (product.isPresent()) {
                    return product;
                }
            }
        }
        return Optional.empty();
    }

    private List<Long> heldProducts() {
        Instant now = clock.instant();
        if (Duration.between(heldLoadedAt, now).compareTo(CANDIDATE_TTL) >= 0) {
            heldProductIds = orderMapper.selectHeldProductIds();
            heldLoadedAt = now;
        }
        return heldProductIds;
    }

    private StockQuoteVO quoteOf(DomesticProductDTO product) {
        List<StockQuoteVO> quotes = quoteService.findQuotes(List.of(product.getTicker()));
        return quotes.isEmpty() ? StockQuoteVO.unavailable(product.getTicker()) : quotes.get(0);
    }

    /** wanted주부터 줄여 가며 체결금액+수수료가 주문 가능 금액(예수금−가정산) 안인 가장 큰 수량. 하나도 안 되면 0. */
    private int affordableQty(AccountDTO account, BigDecimal price, int wanted) {
        BigDecimal buyingPower =
                account.getAmount()
                        .subtract(orderMapper.sumProvisionalAmount(account.getAccountId()));
        LocalDate today = businessClock.now().toLocalDate();
        List<TaxRuleDTO> rules = taxMapper.selectTaxRules();
        for (int qty = wanted; qty >= 1; qty--) {
            BigDecimal cost = StockOrderCalculator.cost(BigDecimal.valueOf(qty), price);
            BigDecimal fee =
                    StockOrderCalculator.commission(
                            cost, account.getOpenedAt().toLocalDate(), today, rules);
            if (cost.add(fee).compareTo(buyingPower) <= 0) {
                return qty;
            }
        }
        return 0;
    }
}
