package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticOrderStatus;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.dto.response.GeneratorOrderResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResultDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeneratorOrderServiceImplTest {
    private static final String CI = "a".repeat(64);
    private static final long ACCOUNT = 70L;
    private static final long SAMSUNG = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 10, 0);

    @Mock AccountMapper accountMapper;
    @Mock StockOrderMapper orderMapper;
    @Mock DomesticProductMapper productMapper;
    @Mock TaxMapper taxMapper;
    @Mock StockQuoteService quoteService;
    @Mock DomesticPurchaseEligibilityService eligibility;
    @Mock BusinessClockService businessClock;
    @Mock StockOrderService orderService;

    GeneratorOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = create(Set.of(), 1L);
    }

    private GeneratorOrderServiceImpl create(Set<String> excluded, long seed) {
        return new GeneratorOrderServiceImpl(
                accountMapper,
                orderMapper,
                productMapper,
                taxMapper,
                quoteService,
                eligibility,
                businessClock,
                orderService,
                Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneId.of("Asia/Seoul")),
                excluded,
                new Random(seed));
    }

    private static AccountDTO account(Status status, String amount) {
        return AccountDTO.builder()
                .accountId(ACCOUNT)
                .customerId(7L)
                .status(status)
                .openedAt(LocalDateTime.of(2026, 3, 24, 9, 0))
                .amount(new BigDecimal(amount))
                .build();
    }

    private static DomesticProductDTO samsung() {
        return DomesticProductDTO.builder()
                .domesticProductId(SAMSUNG)
                .ticker("005930")
                .name("삼성전자")
                .market("KOSPI")
                .type(Type.STOCK)
                .build();
    }

    private static StockQuoteVO quote(String price) {
        return new StockQuoteVO(
                "005930",
                true,
                new BigDecimal(price),
                new BigDecimal(price),
                new BigDecimal("999999"),
                new BigDecimal("1"));
    }

    private static StockOrderResultDTO executed(long id) {
        return new StockOrderResultDTO(
                id,
                null,
                null,
                SAMSUNG,
                "005930",
                "삼성전자",
                DomesticOrderSide.BUY,
                BigDecimal.TEN,
                new BigDecimal("100000"),
                new BigDecimal("-1000040"),
                new BigDecimal("40"),
                BigDecimal.ZERO,
                DomesticOrderStatus.EXECUTED,
                null,
                null,
                NOW);
    }

    private void tradableAccount(String amount) {
        when(accountMapper.selectByCiHash(CI))
                .thenReturn(Optional.of(account(Status.OPENED, amount)));
        when(orderService.findGeneratorOrder(eq(ACCOUNT), anyString()))
                .thenReturn(Optional.empty());
    }

    private void buyCandidates(String price) {
        when(orderMapper.selectHeldProductIds()).thenReturn(List.of(SAMSUNG));
        when(eligibility.isPurchasable(SAMSUNG)).thenReturn(true);
        when(productMapper.selectById(SAMSUNG)).thenReturn(Optional.of(samsung()));
        when(quoteService.findQuotes(List.of("005930"))).thenReturn(List.of(quote(price)));
        when(orderMapper.sumProvisionalAmount(ACCOUNT)).thenReturn(BigDecimal.ZERO);
        when(businessClock.now()).thenReturn(NOW);
        when(taxMapper.selectTaxRules())
                .thenReturn(
                        List.of(
                                rule(TaxRuleType.COMMISSION_RIA, "0.0040"),
                                rule(TaxRuleType.COMMISSION_BASE, "0.0150"),
                                rule(TaxRuleType.COMMISSION_RIA_YEARS, "1.0000")));
    }

    private static TaxRuleDTO rule(TaxRuleType type, String value) {
        return new TaxRuleDTO(
                1L,
                type,
                new BigDecimal(value),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(9999, 12, 31));
    }

    @Test
    void 매수는_보유_상품_중_하나를_1에서_25주_사이로_주문한다() {
        tradableAccount("100000000");
        buyCandidates("100000");
        when(orderService.placeGeneratorOrder(
                        eq(ACCOUNT),
                        any(),
                        any(),
                        eq(DomesticOrderSide.BUY),
                        anyInt(),
                        eq("GEN-1")))
                .thenReturn(executed(11L));

        GeneratorOrderResponseDTO r = service.order(CI, DomesticOrderSide.BUY, "GEN-1");

        assertThat(r.status()).isEqualTo("EXECUTED");
        assertThat(r.domesticOrderId()).isEqualTo(11L);
        assertThat(r.ticker()).isEqualTo("005930");
        ArgumentCaptor<Integer> qty = ArgumentCaptor.forClass(Integer.class);
        verify(orderService)
                .placeGeneratorOrder(
                        eq(ACCOUNT),
                        any(),
                        any(),
                        eq(DomesticOrderSide.BUY),
                        qty.capture(),
                        eq("GEN-1"));
        assertThat(qty.getValue()).isBetween(1, 25);
    }

    @Test
    void 매수_수량은_수수료를_포함해_살_수_있는_만큼으로_줄어든다() {
        // 3주 값 + 수수료만 살 수 있는 예수금: 100,000×3 = 300,000, 수수료 0.004% = 12 → 300,012
        tradableAccount("300012");
        buyCandidates("100000");
        when(orderService.placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(executed(12L));
        for (long seed = 0; seed < 30; seed++) {
            create(Set.of(), seed).order(CI, DomesticOrderSide.BUY, "GEN-" + seed);
        }
        ArgumentCaptor<Integer> qty = ArgumentCaptor.forClass(Integer.class);
        verify(orderService, org.mockito.Mockito.times(30))
                .placeGeneratorOrder(any(), any(), any(), any(), qty.capture(), anyString());
        assertThat(qty.getAllValues()).allMatch(q -> q >= 1 && q <= 3);
    }

    @Test
    void 한_주도_못_사면_INSUFFICIENT_CASH이고_주문을_넣지_않는다() {
        tradableAccount("100");
        buyCandidates("100000");

        GeneratorOrderResponseDTO r = service.order(CI, DomesticOrderSide.BUY, "GEN-2");

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.rejectReason()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(r.domesticOrderId()).isNull();
        verify(orderService, never())
                .placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void 같은_키로_다시_오면_처음_결과를_주고_새로_고르지_않는다() {
        when(accountMapper.selectByCiHash(CI)).thenReturn(Optional.of(account(Status.OPENED, "1")));
        when(orderService.findGeneratorOrder(ACCOUNT, "GEN-3"))
                .thenReturn(Optional.of(executed(77L)));

        GeneratorOrderResponseDTO r = service.order(CI, DomesticOrderSide.BUY, "GEN-3");

        assertThat(r.status()).isEqualTo("EXECUTED");
        assertThat(r.domesticOrderId()).isEqualTo(77L);
        verify(orderMapper, never()).selectHeldProductIds();
        verify(orderService, never())
                .placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void 매도는_그_계좌_보유_수량_안에서만() {
        tradableAccount("100000000");
        when(orderMapper.selectHeldBalancesByAccount(ACCOUNT))
                .thenReturn(
                        List.of(
                                StockBalanceVO.builder()
                                        .accountId(ACCOUNT)
                                        .domesticProductId(SAMSUNG)
                                        .qty(new BigDecimal("3"))
                                        .status(DomesticStockStatus.HOLDING)
                                        .build()));
        when(productMapper.selectById(SAMSUNG)).thenReturn(Optional.of(samsung()));
        when(quoteService.findQuotes(List.of("005930"))).thenReturn(List.of(quote("100000")));
        when(orderService.placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(executed(13L));
        for (long seed = 0; seed < 30; seed++) {
            create(Set.of(), seed).order(CI, DomesticOrderSide.SELL, "GEN-S" + seed);
        }
        ArgumentCaptor<Integer> qty = ArgumentCaptor.forClass(Integer.class);
        verify(orderService, org.mockito.Mockito.times(30))
                .placeGeneratorOrder(
                        any(),
                        any(),
                        any(),
                        eq(DomesticOrderSide.SELL),
                        qty.capture(),
                        anyString());
        assertThat(qty.getAllValues()).allMatch(q -> q >= 1 && q <= 3);
    }

    @Test
    void 보유가_없거나_거래제한이면_NO_HOLDING() {
        tradableAccount("100000000");
        when(orderMapper.selectHeldBalancesByAccount(ACCOUNT))
                .thenReturn(
                        List.of(
                                StockBalanceVO.builder()
                                        .accountId(ACCOUNT)
                                        .domesticProductId(SAMSUNG)
                                        .qty(new BigDecimal("3"))
                                        .status(DomesticStockStatus.TRADE_RESTRICTED)
                                        .build()));

        GeneratorOrderResponseDTO r = service.order(CI, DomesticOrderSide.SELL, "GEN-4");

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.rejectReason()).isEqualTo("NO_HOLDING");
        verify(orderService, never())
                .placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void 제외_고객은_계좌를_보기_전에_거절한다() {
        GeneratorOrderResponseDTO r =
                create(Set.of(CI), 1L).order(CI, DomesticOrderSide.BUY, "GEN-5");

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.rejectReason()).isEqualTo("EXCLUDED_CUSTOMER");
        verify(accountMapper, never()).selectByCiHash(anyString());
        verify(orderService, never())
                .placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void 계좌가_없거나_거래할_수_없는_상태면_NO_ACCOUNT() {
        when(accountMapper.selectByCiHash(CI))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "100000000")));
        assertThat(service.order(CI, DomesticOrderSide.BUY, "GEN-6").rejectReason())
                .isEqualTo("NO_ACCOUNT");

        when(accountMapper.selectByCiHash("b".repeat(64))).thenReturn(Optional.empty());
        assertThat(service.order("b".repeat(64), DomesticOrderSide.BUY, "GEN-7").rejectReason())
                .isEqualTo("NO_ACCOUNT");
        verify(orderService, never())
                .placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void 살_수_있는_상품이_없으면_NOT_ELIGIBLE() {
        tradableAccount("100000000");
        when(orderMapper.selectHeldProductIds()).thenReturn(List.of(SAMSUNG));
        when(eligibility.isPurchasable(SAMSUNG)).thenReturn(false);

        GeneratorOrderResponseDTO r = service.order(CI, DomesticOrderSide.BUY, "GEN-8");

        assertThat(r.rejectReason()).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void 매수_후보_목록은_1분_동안_다시_읽지_않는다() {
        tradableAccount("100000000");
        buyCandidates("100000");
        when(orderService.placeGeneratorOrder(any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(executed(14L));
        service.order(CI, DomesticOrderSide.BUY, "GEN-9");
        service.order(CI, DomesticOrderSide.BUY, "GEN-10");
        verify(orderMapper, org.mockito.Mockito.times(1)).selectHeldProductIds();
    }
}
