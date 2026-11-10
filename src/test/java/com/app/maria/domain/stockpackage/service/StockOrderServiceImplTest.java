package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.app.maria.domain.stockpackage.dto.request.StockOrderRequestDTO;
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
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class StockOrderServiceImplTest {
    private static final long CUSTOMER = 7L;
    private static final long ACCOUNT = 70L;
    private static final long PACKAGE = 5L;
    private static final long SAMSUNG = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 10, 0);
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate FOREVER = LocalDate.of(9999, 12, 31);

    @Mock StockPackageMapper packageMapper;
    @Mock StockOrderMapper orderMapper;
    @Mock AccountMapper accountMapper;
    @Mock DomesticProductMapper productMapper;
    @Mock TaxMapper taxMapper;
    @Mock StockQuoteService quoteService;
    @Mock DomesticPurchaseEligibilityService eligibility;
    @Mock BusinessClockService clock;
    @Mock PlatformTransactionManager txManager;
    @Mock SecuritiesOrderRecorder recorder;

    StockOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new StockOrderServiceImpl(
                        packageMapper,
                        orderMapper,
                        accountMapper,
                        productMapper,
                        taxMapper,
                        quoteService,
                        eligibility,
                        clock,
                        txManager,
                        recorder,
                        false);
    }

    private static AccountDTO account(Status status, String amount) {
        return AccountDTO.builder()
                .accountId(ACCOUNT)
                .customerId(CUSTOMER)
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

    private static StockOrderRequestDTO request(String key, DomesticOrderSide side, String qty) {
        return new StockOrderRequestDTO(
                key, List.of(new StockOrderRequestDTO.Item(SAMSUNG, side, new BigDecimal(qty))));
    }

    /** 패키지·계좌·상품·현재가·수수료 규칙이 다 준비된 정상 상태. */
    private void prepare(AccountDTO account, StockQuoteVO quote) {
        when(packageMapper.selectByIdAndCustomerId(PACKAGE, CUSTOMER))
                .thenReturn(Optional.of(StockPackageVO.builder().stockPackageId(PACKAGE).build()));
        when(packageMapper.selectItemsByPackageId(PACKAGE))
                .thenReturn(
                        List.of(StockPackageItemVO.builder().domesticProductId(SAMSUNG).build()));
        when(accountMapper.selectByCustomerId(CUSTOMER)).thenReturn(Optional.of(account));
        when(clock.now()).thenReturn(NOW);
        lenient()
                .when(accountMapper.selectCiHashByCustomerId(CUSTOMER))
                .thenReturn(Optional.of("a".repeat(64)));
        when(productMapper.selectById(SAMSUNG)).thenReturn(Optional.of(samsung()));
        when(quoteService.findQuotes(List.of("005930"))).thenReturn(List.of(quote));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.of(account));
        when(taxMapper.selectTaxRules())
                .thenReturn(
                        List.of(
                                rule(TaxRuleType.COMMISSION_RIA, "0.0040"),
                                rule(TaxRuleType.COMMISSION_BASE, "0.0150"),
                                rule(TaxRuleType.COMMISSION_RIA_YEARS, "1.0000"),
                                rule(TaxRuleType.SEC_TX_TAX_STOCK, "0.2000")));
        when(orderMapper.sumProvisionalAmount(ACCOUNT)).thenReturn(BigDecimal.ZERO);
        // 주문 처리 전에 예외가 나는 경우(패키지 없음 등)에는 쓰이지 않는다
        lenient()
                .when(orderMapper.selectByIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    private static TaxRuleDTO rule(TaxRuleType type, String value) {
        return new TaxRuleDTO(1L, type, new BigDecimal(value), FROM, FOREVER);
    }

    private static StockQuoteVO quote(String price) {
        return new StockQuoteVO(
                "005930",
                true,
                new BigDecimal(price),
                new BigDecimal("97500"),
                new BigDecimal("126750"),
                new BigDecimal("68250"));
    }

    @Test
    void 매수가_체결되면_수수료를_포함해_예수금이_줄고_보유가_생긴다() {
        prepare(account(Status.OPENED, "10000000"), quote("100000"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        StockOrderResponseDTO r =
                service.place(CUSTOMER, PACKAGE, request("k1", DomesticOrderSide.BUY, "10"));

        StockOrderResultDTO o = r.orders().get(0);
        assertThat(o.status()).isEqualTo(DomesticOrderStatus.EXECUTED);
        assertThat(o.amount()).isEqualByComparingTo("-1000040"); // 100만 + 수수료 40
        assertThat(o.feeAmount()).isEqualByComparingTo("40");
        assertThat(o.taxAmount()).isEqualByComparingTo("0");
        assertThat(r.accountAmount()).isEqualByComparingTo("8999960");
        ArgumentCaptor<StockBalanceVO> balance = ArgumentCaptor.forClass(StockBalanceVO.class);
        verify(orderMapper).insertBalance(balance.capture());
        assertThat(balance.getValue().getQty()).isEqualByComparingTo("10");
        assertThat(balance.getValue().getAvgPurchasePrice()).isEqualByComparingTo("100000");
        assertThat(balance.getValue().getStatus()).isEqualTo(DomesticStockStatus.HOLDING);
        verify(orderMapper).applyOrderAmount(eq(ACCOUNT), any());
    }

    @Test
    void 주문_가능_금액은_가정산을_뺀_금액이고_수수료까지_넘으면_예수금부족으로_거부한다() {
        // 예수금 1,000,010 − 가정산 0, 체결금액 1,000,000 + 수수료 40 = 1,000,040 > 1,000,010
        prepare(account(Status.OPENED, "1000010"), quote("100000"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("k2", DomesticOrderSide.BUY, "10"))
                        .orders()
                        .get(0);

        assertThat(o.status()).isEqualTo(DomesticOrderStatus.REJECTED);
        assertThat(o.rejectReason()).isEqualTo(StockOrderRejectReason.INSUFFICIENT_CASH.name());
        assertThat(o.price()).isNull();
        assertThat(o.amount()).isNull();
        assertThat(o.feeAmount()).isEqualByComparingTo("0");
        verify(orderMapper, never()).applyOrderAmount(anyLong(), any());
        verify(orderMapper, never()).insertBalance(any());
    }

    /** 패키지 총액 1,000만 원, 삼성전자 목표 비중 1%(= 한도 10만 원)인 패키지. */
    private void prepareWeightedPackage() {
        prepare(account(Status.OPENED, "10000000"), quote("100000"));
        lenient().when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        lenient()
                .when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG))
                .thenReturn(Optional.empty());
        when(packageMapper.selectByIdAndCustomerId(PACKAGE, CUSTOMER))
                .thenReturn(
                        Optional.of(
                                StockPackageVO.builder()
                                        .stockPackageId(PACKAGE)
                                        .totalAmount(new BigDecimal("10000000"))
                                        .build()));
        when(packageMapper.selectItemsByPackageId(PACKAGE))
                .thenReturn(
                        List.of(
                                StockPackageItemVO.builder()
                                        .domesticProductId(SAMSUNG)
                                        .weight(BigDecimal.ONE)
                                        .build()));
    }

    @Test
    void 목표_비중이_정한_금액을_넘게_사려_하면_예수금이_충분해도_WEIGHT_LIMIT_EXCEEDED로_거부한다() {
        prepareWeightedPackage();

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("w1", DomesticOrderSide.BUY, "10"))
                        .orders()
                        .get(0);

        assertThat(o.status()).isEqualTo(DomesticOrderStatus.REJECTED);
        assertThat(o.rejectReason()).isEqualTo(StockOrderRejectReason.WEIGHT_LIMIT_EXCEEDED.name());
        verify(orderMapper, never()).insertBalance(any());
    }

    @Test
    void 목표_비중_한도_안의_매수는_체결된다() {
        prepareWeightedPackage();

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("w2", DomesticOrderSide.BUY, "1"))
                        .orders()
                        .get(0);

        assertThat(o.status()).isEqualTo(DomesticOrderStatus.EXECUTED);
    }

    @Test
    void 주문_도중_패키지가_사라지면_한도_없이_체결하지_않고_패키지없음으로_막는다() {
        prepareWeightedPackage();
        // 첫 조회(place의 존재 확인)는 있고, 두 번째 조회(트랜잭션 안의 한도 계산)는 이미 삭제된 상태
        when(packageMapper.selectByIdAndCustomerId(PACKAGE, CUSTOMER))
                .thenReturn(
                        Optional.of(
                                StockPackageVO.builder()
                                        .stockPackageId(PACKAGE)
                                        .totalAmount(new BigDecimal("10000000"))
                                        .build()),
                        Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.place(
                                        CUSTOMER,
                                        PACKAGE,
                                        request("w4", DomesticOrderSide.BUY, "1")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_PACKAGE_NOT_FOUND));
        verify(orderMapper, never()).insertBalance(any());
    }

    @Test
    void 이미_담은_금액은_한도에서_빼고_남은_만큼만_살_수_있다() {
        prepareWeightedPackage();
        when(orderMapper.selectExecutedByPackageAsc(PACKAGE))
                .thenReturn(
                        List.of(
                                StockOrderVO.builder()
                                        .domesticProductId(SAMSUNG)
                                        .side(DomesticOrderSide.BUY)
                                        .qty(BigDecimal.ONE)
                                        .price(new BigDecimal("100000"))
                                        .build()));

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("w3", DomesticOrderSide.BUY, "1"))
                        .orders()
                        .get(0);

        assertThat(o.status()).isEqualTo(DomesticOrderStatus.REJECTED);
        assertThat(o.rejectReason()).isEqualTo(StockOrderRejectReason.WEIGHT_LIMIT_EXCEEDED.name());
    }

    @Test
    void 가정산_금액은_주문에_쓸_수_없다() {
        prepare(account(Status.OPENED, "2000000"), quote("100000"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());
        when(orderMapper.sumProvisionalAmount(ACCOUNT)).thenReturn(new BigDecimal("1500000"));

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("k3", DomesticOrderSide.BUY, "10"))
                        .orders()
                        .get(0);

        assertThat(o.rejectReason()).isEqualTo("INSUFFICIENT_CASH");
    }

    @Test
    void 편입_불가_상품은_현재가와_상관없이_NOT_ELIGIBLE이다() {
        prepare(account(Status.OPENED, "10000000"), quote("100000"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(false);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("k4", DomesticOrderSide.BUY, "1"))
                        .orders()
                        .get(0);

        assertThat(o.rejectReason()).isEqualTo("NOT_ELIGIBLE");
        assertThat(o.rejectMessage()).isEqualTo(StockOrderRejectReason.NOT_ELIGIBLE.getMessage());
    }

    @Test
    void 현재가를_못받으면_PRICE_UNAVAILABLE로_거부한다() {
        prepare(account(Status.OPENED, "10000000"), StockQuoteVO.unavailable("005930"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("k5", DomesticOrderSide.BUY, "1"))
                        .orders()
                        .get(0);

        assertThat(o.rejectReason()).isEqualTo("PRICE_UNAVAILABLE");
        verify(orderMapper, never()).applyOrderAmount(anyLong(), any());
    }

    @Test
    void 매도는_수수료와_거래세를_빼고_일부매도_상태가_된다() {
        prepare(account(Status.OPENED, "0"), quote("100000"));
        StockBalanceVO held =
                StockBalanceVO.builder()
                        .domesticStockBalanceId(9L)
                        .accountId(ACCOUNT)
                        .domesticProductId(SAMSUNG)
                        .qty(new BigDecimal("10"))
                        .status(DomesticStockStatus.HOLDING)
                        .avgPurchasePrice(new BigDecimal("90000"))
                        .build();
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.of(held));

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("k6", DomesticOrderSide.SELL, "4"))
                        .orders()
                        .get(0);

        assertThat(o.status()).isEqualTo(DomesticOrderStatus.EXECUTED);
        assertThat(o.feeAmount()).isEqualByComparingTo("16");
        assertThat(o.taxAmount()).isEqualByComparingTo("800");
        assertThat(o.amount()).isEqualByComparingTo("399184");
        assertThat(held.getQty()).isEqualByComparingTo("6");
        assertThat(held.getStatus()).isEqualTo(DomesticStockStatus.PARTIALLY_SOLD);
        assertThat(held.getAvgPurchasePrice()).isEqualByComparingTo("90000");
        verify(orderMapper).updateBalance(held);
    }

    @Test
    void 보유보다_많이_팔면_INSUFFICIENT_QTY로_거부한다() {
        prepare(account(Status.OPENED, "0"), quote("100000"));
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        StockOrderResultDTO o =
                service.place(CUSTOMER, PACKAGE, request("k7", DomesticOrderSide.SELL, "1"))
                        .orders()
                        .get(0);

        assertThat(o.rejectReason()).isEqualTo("INSUFFICIENT_QTY");
    }

    @Test
    void 같은_주문키로_다시_보내면_새로_체결하지_않고_처음_결과를_돌려준다() {
        prepare(account(Status.OPENED, "10000000"), quote("100000"));
        StockOrderVO prior =
                StockOrderVO.builder()
                        .domesticOrderId(55L)
                        .domesticProductId(SAMSUNG)
                        .side(DomesticOrderSide.BUY)
                        .qty(new BigDecimal("10"))
                        .price(new BigDecimal("100000"))
                        .amount(new BigDecimal("-1000040"))
                        .feeAmount(new BigDecimal("40"))
                        .taxAmount(BigDecimal.ZERO)
                        .status(DomesticOrderStatus.EXECUTED)
                        .build();
        when(orderMapper.selectByIdempotencyKey(ACCOUNT, "k8:1")).thenReturn(Optional.of(prior));

        StockOrderResponseDTO r =
                service.place(CUSTOMER, PACKAGE, request("k8", DomesticOrderSide.BUY, "10"));

        assertThat(r.orders().get(0).orderId()).isEqualTo(55L);
        verify(orderMapper, never()).insertOrder(any());
        verify(orderMapper, never()).applyOrderAmount(anyLong(), any());
    }

    @Test
    void 패키지에_없는_종목_소수_수량_개설전_계좌는_거부하고_체결하지_않는다() {
        when(packageMapper.selectByIdAndCustomerId(PACKAGE, CUSTOMER))
                .thenReturn(Optional.of(StockPackageVO.builder().build()));
        when(packageMapper.selectItemsByPackageId(PACKAGE))
                .thenReturn(List.of(StockPackageItemVO.builder().domesticProductId(99L).build()));

        assertThatThrownBy(
                        () ->
                                service.place(
                                        CUSTOMER,
                                        PACKAGE,
                                        request("k9", DomesticOrderSide.BUY, "1")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_ORDER_ITEM_NOT_IN_PACKAGE));

        assertThatThrownBy(
                        () ->
                                service.place(
                                        CUSTOMER,
                                        PACKAGE,
                                        request("k9", DomesticOrderSide.BUY, "1.5")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_ORDER_REQUEST_INVALID));

        when(packageMapper.selectItemsByPackageId(PACKAGE))
                .thenReturn(
                        List.of(StockPackageItemVO.builder().domesticProductId(SAMSUNG).build()));
        when(accountMapper.selectByCustomerId(CUSTOMER))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "100")));
        assertThatThrownBy(
                        () ->
                                service.place(
                                        CUSTOMER,
                                        PACKAGE,
                                        request("k9", DomesticOrderSide.BUY, "1")))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_ORDER_ACCOUNT_NOT_OPEN));

        verify(orderMapper, never()).insertOrder(any());
    }

    @Test
    void 요율_조회는_오늘_기준_수수료율과_거래세율을_준다() {
        when(accountMapper.selectByCustomerId(CUSTOMER))
                .thenReturn(Optional.of(account(Status.OPENED, "0")));
        when(clock.now()).thenReturn(NOW);
        when(taxMapper.selectTaxRules())
                .thenReturn(
                        List.of(
                                rule(TaxRuleType.COMMISSION_RIA, "0.0040"),
                                rule(TaxRuleType.COMMISSION_BASE, "0.0150"),
                                rule(TaxRuleType.COMMISSION_RIA_YEARS, "1.0000"),
                                rule(TaxRuleType.SEC_TX_TAX_STOCK, "0.2000")));

        var fees = service.getFees(CUSTOMER);

        assertThat(fees.commissionRatePercent()).isEqualByComparingTo("0.0040");
        assertThat(fees.securitiesTaxRatePercent()).isEqualByComparingTo("0.2000");
    }

    /** 생성기 주문이 실제로 쓰는 것만: 계좌 락, 시계, 수수료 규칙, 가정산, 키 조회. */
    private void prepareGenerator(AccountDTO account) {
        when(clock.now()).thenReturn(NOW);
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.of(account));
        when(taxMapper.selectTaxRules())
                .thenReturn(
                        List.of(
                                rule(TaxRuleType.COMMISSION_RIA, "0.0040"),
                                rule(TaxRuleType.COMMISSION_BASE, "0.0150"),
                                rule(TaxRuleType.COMMISSION_RIA_YEARS, "1.0000"),
                                rule(TaxRuleType.SEC_TX_TAX_STOCK, "0.2000")));
        when(orderMapper.sumProvisionalAmount(ACCOUNT)).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void 생성기_주문은_GENERATOR_채널로_패키지_없이_체결되고_수수료까지_주문가능금액_안() {
        prepareGenerator(account(Status.OPENED, "10000000"));
        when(orderMapper.selectByIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        StockOrderResultDTO o =
                service.placeGeneratorOrder(
                        ACCOUNT, samsung(), quote("100000"), DomesticOrderSide.BUY, 10, "GEN-1");

        assertThat(o.status()).isEqualTo(DomesticOrderStatus.EXECUTED);
        ArgumentCaptor<StockOrderVO> saved = ArgumentCaptor.forClass(StockOrderVO.class);
        verify(orderMapper).insertOrder(saved.capture());
        assertThat(saved.getValue().getChannel()).isEqualTo("GENERATOR");
        assertThat(saved.getValue().getStockPackageId()).isNull();
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("GEN-1:1");
        assertThat(o.amount().negate()).isLessThanOrEqualTo(new BigDecimal("10000000"));
    }

    @Test
    void 생성기_주문_같은_키는_다시_체결하지_않고_처음_결과를_준다() {
        prepareGenerator(account(Status.OPENED, "10000000"));
        StockOrderVO prior =
                StockOrderVO.builder()
                        .domesticOrderId(99L)
                        .accountId(ACCOUNT)
                        .domesticProductId(SAMSUNG)
                        .side(DomesticOrderSide.BUY)
                        .qty(BigDecimal.TEN)
                        .channel("GENERATOR")
                        .status(DomesticOrderStatus.EXECUTED)
                        .build();
        when(orderMapper.selectByIdempotencyKey(ACCOUNT, "GEN-1:1")).thenReturn(Optional.of(prior));

        StockOrderResultDTO o =
                service.placeGeneratorOrder(
                        ACCOUNT, samsung(), quote("100000"), DomesticOrderSide.BUY, 10, "GEN-1");

        assertThat(o.orderId()).isEqualTo(99L);
        verify(orderMapper, never()).insertOrder(any());
        verify(orderMapper, never()).applyOrderAmount(anyLong(), any());
    }

    @Test
    void 생성기_주문이_이미_있는지_키로_찾는다() {
        StockOrderVO prior = StockOrderVO.builder().domesticOrderId(5L).build();
        when(orderMapper.selectByIdempotencyKey(ACCOUNT, "GEN-9:1")).thenReturn(Optional.of(prior));
        assertThat(service.findGeneratorOrder(ACCOUNT, "GEN-9").orElseThrow().orderId())
                .isEqualTo(5L);
        assertThat(service.findGeneratorOrder(ACCOUNT, "GEN-0")).isEmpty();
    }

    private static final String CI_HASH = "a".repeat(64);

    private void customerBuyReady() {
        prepare(account(Status.OPENED, "10000000"), quote("100000"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER)).thenReturn(Optional.of(CI_HASH));
    }

    @Test
    void 고객_주문이_체결되면_증권사에_거래를_기록한다() {
        customerBuyReady();
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            StockOrderVO saved = inv.getArgument(0);
                            saved.setDomesticOrderId(555L);
                            return 1;
                        })
                .when(orderMapper)
                .insertOrder(any());

        service.place(CUSTOMER, PACKAGE, request("k-rec", DomesticOrderSide.BUY, "10"));

        ArgumentCaptor<StockOrderVO> sent = ArgumentCaptor.forClass(StockOrderVO.class);
        verify(recorder).record(eq(CI_HASH), sent.capture());
        assertThat(sent.getValue().getDomesticOrderId()).isEqualTo(555L);
        assertThat(sent.getValue().getTicker()).isEqualTo("005930");
    }

    @Test
    void 증권사_전송_실패는_주문을_롤백하지_않고_재시도_대상으로_남긴다() {
        customerBuyReady();
        when(recorder.record(any(), any())).thenReturn(false);

        StockOrderResponseDTO response =
                service.place(CUSTOMER, PACKAGE, request("k-fail", DomesticOrderSide.BUY, "10"));

        assertThat(response.orders()).hasSize(1);
        verify(orderMapper).insertOrder(any());
    }

    @Test
    void 거부된_주문은_증권사에_기록하지_않는다() {
        prepare(account(Status.OPENED, "1000010"), quote("100000"));
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        service.place(CUSTOMER, PACKAGE, request("k-rej", DomesticOrderSide.BUY, "10"));

        verify(recorder, never()).record(any(), any());
    }

    @Test
    void 생성기_주문은_증권사에_다시_기록하지_않는다() {
        prepareGenerator(account(Status.OPENED, "10000000"));
        when(orderMapper.selectByIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(eligibility.isPurchasable(any(), any(), any())).thenReturn(true);
        when(orderMapper.selectBalanceForUpdate(ACCOUNT, SAMSUNG)).thenReturn(Optional.empty());

        service.placeGeneratorOrder(
                ACCOUNT, samsung(), quote("100000"), DomesticOrderSide.BUY, 10, "GEN-9");

        verify(recorder, never()).record(any(), any());
    }
}
