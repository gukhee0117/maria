package com.app.maria.domain.asset.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.AssetMonthlyPurchaseDTO;
import com.app.maria.domain.asset.mapper.AssetBriefingMapper;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.domain.asset.price.AssetPriceProvider.Quote;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.exchange.RateWithDate;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.KisDomesticPriceClient;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class AssetBriefingServiceImplTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 30);
    @Mock AccountMapper accountMapper;
    @Mock AssetBriefingMapper mapper;
    @Mock BusinessClockService clock;
    @Mock AssetPriceProvider priceProvider;
    @Mock TaxMapper taxMapper;
    @Mock KisDomesticPriceClient kisDomesticPriceClient;
    @Mock KisPriceClient kisPriceClient;
    @Mock ExchangeRateClient exchangeRateClient;
    AssetBriefingServiceImpl service;

    @BeforeEach
    void setup() {
        service =
                new AssetBriefingServiceImpl(
                        new AssetBriefingSnapshotReader(accountMapper, mapper, clock, taxMapper),
                        priceProvider,
                        kisDomesticPriceClient,
                        kisPriceClient,
                        exchangeRateClient);
        // 기본: 국내 현재가·기준가는 못 받는 상태(해외 보유 없음 → 해외 평가 0). 개별 테스트가 덮어쓴다.
        lenient()
                .when(kisDomesticPriceClient.getQuote(any()))
                .thenThrow(new AppException(ErrorType.KIS_PRICE_NOT_FOUND, "x"));
    }

    private void stubAmounts(String cost, String inflow) {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder()
                                        .accountId(11L)
                                        .customerId(7L)
                                        .status(Status.OPENED)
                                        .amount(new BigDecimal("12345"))
                                        .build()));
        when(clock.now()).thenReturn(NOW);
        when(mapper.sumHoldingCost(11L)).thenReturn(new BigDecimal(cost));
        when(mapper.sumFinalizedInflow(11L, NOW)).thenReturn(new BigDecimal(inflow));
        when(mapper.selectMonthlyPurchases(11L, NOW)).thenReturn(List.of());
        when(mapper.selectHoldings(11L))
                .thenReturn(
                        new BigDecimal(cost).signum() == 0
                                ? List.of()
                                : List.of(holding("005930", "1")));
    }

    @Test
    void reportsCostAndRatioWithoutInventingValuation() {
        stubAmounts("1980.75", "5000");
        AssetMonthlyPurchaseDTO month = new AssetMonthlyPurchaseDTO();
        month.setPurchaseYear(2026);
        month.setPurchaseMonth(3);
        month.setBuyAmount(new BigDecimal("700"));
        when(mapper.selectMonthlyPurchases(11L, NOW)).thenReturn(List.of(month));

        var result = service.getBriefing(7L);

        assertThat(result.getAccountId()).isEqualTo(11L);
        assertThat(result.getCashAmount()).isEqualByComparingTo("12345");
        assertThat(result.getInvestmentCostAmount()).isEqualByComparingTo("1980.75");
        assertThat(result.getCumulativeInflowAmount()).isEqualByComparingTo("5000");
        // 1980.75 / 5000 * 100 = 39.615 -> 39.62. 예수금을 분자에 포함하지 않는다.
        assertThat(result.getInvestmentRatio()).isEqualByComparingTo("39.62");
        assertThat(result.getDomesticValuationAmount()).isNull();
        assertThat(result.getValuationProfit()).isNull();
        assertThat(result.getValuationReturnRate()).isNull();
        assertThat(result.getPackageDeviation()).isNull();
        assertThat(result.getUnavailableFields())
                .containsExactly(
                        "domesticValuationAmount",
                        "valuationProfit",
                        "valuationReturnRate",
                        "totalAssetAmount",
                        "domesticDayChangeAmount",
                        "domesticDayChangeRate",
                        "dayChangeBaseDate",
                        "packageDeviation");
        assertThat(result.getMonthlyPurchases())
                .singleElement()
                .satisfies(
                        value -> {
                            assertThat(value.month()).isEqualTo("2026-03");
                            assertThat(value.buyAmount()).isEqualByComparingTo("700");
                        });
        assertThat(result.getCalculatedAt()).isEqualTo(NOW);
        verify(clock).now();
        verify(mapper).sumHoldingCost(11L);
        verify(mapper).sumFinalizedInflow(11L, NOW);
        verify(mapper).selectMonthlyPurchases(11L, NOW);
        verify(mapper).selectHoldings(11L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void zeroInflowIsUnknownRatioNotZeroPercent() {
        stubAmounts("0", "0");
        var result = service.getBriefing(7L);
        assertThat(result.getInvestmentCostAmount()).isEqualByComparingTo("0");
        assertThat(result.getCumulativeInflowAmount()).isEqualByComparingTo("0");
        assertThat(result.getInvestmentRatio()).isNull();
        assertThat(result.getDomesticValuationAmount()).isEqualByComparingTo("0");
        assertThat(result.getValuationProfit()).isEqualByComparingTo("0");
        assertThat(result.getValuationReturnRate()).isNull();
        assertThat(result.getValuationPriceFrom()).isNull();
        assertThat(result.getValuationPriceTo()).isNull();
        assertThat(result.getUnavailableFields()).contains("investmentRatio");
        assertThat(result.getMonthlyPurchases()).isEmpty();
        verifyNoInteractions(priceProvider);
    }

    @Test
    void reinvestmentRatioIsNotSilentlyClampedTo100Percent() {
        stubAmounts("15000", "10000");
        assertThat(service.getBriefing(7L).getInvestmentRatio()).isEqualByComparingTo("150");
    }

    @Test
    void cashOnlyAccountWithInflowHasZeroInvestmentRatio() {
        stubAmounts("0", "10000");
        assertThat(service.getBriefing(7L).getInvestmentRatio()).isEqualByComparingTo("0");
    }

    @Test
    void missingAccountDoesNotQueryOtherCustomersAssets() {
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getBriefing(7L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error ->
                                assertThat(error.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));
        verifyNoInteractions(mapper, clock);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = "OPENED", mode = EnumSource.Mode.EXCLUDE)
    void nonOpenedAccountsCannotGetBriefing(Status status) {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(
                        Optional.of(AccountDTO.builder().accountId(11L).status(status).build()));
        assertThatThrownBy(() -> service.getBriefing(7L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error ->
                                assertThat(error.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));
        verifyNoInteractions(mapper, clock);
    }

    @Test
    void failedDatabaseReadDoesNotBecomeAnEmptySuccessfulBriefing() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder().accountId(11L).status(Status.OPENED).build()));
        when(clock.now()).thenReturn(NOW);
        when(mapper.sumHoldingCost(11L))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.getBriefing(7L))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(mapper, never()).selectMonthlyPurchases(anyLong(), any());
    }

    private static AssetHoldingDTO holding(String ticker, String qty) {
        AssetHoldingDTO holding = new AssetHoldingDTO();
        holding.setTicker(ticker);
        holding.setQty(new BigDecimal(qty));
        return holding;
    }

    @Test
    void calculatesWeightedPortfolioGainFromAllPricesWithoutIncludingCash() {
        stubAmounts("800", "2000");
        when(mapper.selectHoldings(11L))
                .thenReturn(List.of(holding("005930", "2"), holding("000660", "3")));
        when(priceProvider.getPrices(Set.of("005930", "000660")))
                .thenReturn(
                        Map.of(
                                "005930", new Quote(new BigDecimal("130"), NOW.minusMinutes(2)),
                                "000660", new Quote(new BigDecimal("220"), NOW.minusMinutes(1))));
        var result = service.getBriefing(7L);
        // 2*130 + 3*220 = 920; cost=800; gain=120; rate=15%, not the mean of 30% and 10%.
        assertThat(result.getDomesticValuationAmount()).isEqualByComparingTo("920");
        assertThat(result.getValuationProfit()).isEqualByComparingTo("120");
        assertThat(result.getValuationReturnRate()).isEqualByComparingTo("15.00");
        assertThat(result.getCashAmount()).isEqualByComparingTo("12345");
        assertThat(result.getValuationPriceFrom()).isEqualTo(NOW.minusMinutes(2));
        assertThat(result.getValuationPriceTo()).isEqualTo(NOW.minusMinutes(1));
        assertThat(result.getUnavailableFields())
                .containsExactly(
                        "domesticDayChangeAmount",
                        "domesticDayChangeRate",
                        "dayChangeBaseDate",
                        "packageDeviation");
    }

    @Test
    void lossesAreReturnedAsNegativeRatherThanClampedToZero() {
        stubAmounts("800", "2000");
        when(mapper.selectHoldings(11L))
                .thenReturn(List.of(holding("005930", "2"), holding("000660", "3")));
        when(priceProvider.getPrices(anySet()))
                .thenReturn(
                        Map.of(
                                "005930", new Quote(new BigDecimal("80"), NOW),
                                "000660", new Quote(new BigDecimal("150"), NOW)));
        var result = service.getBriefing(7L);
        assertThat(result.getDomesticValuationAmount()).isEqualByComparingTo("610");
        assertThat(result.getValuationProfit()).isEqualByComparingTo("-190");
        assertThat(result.getValuationReturnRate()).isEqualByComparingTo("-23.75");
    }

    @Test
    void missingOnePriceNeverReturnsPartialPortfolioTotal() {
        stubAmounts("800", "2000");
        when(mapper.selectHoldings(11L))
                .thenReturn(List.of(holding("005930", "2"), holding("000660", "3")));
        when(priceProvider.getPrices(anySet()))
                .thenReturn(Map.of("005930", new Quote(new BigDecimal("130"), NOW)));
        var result = service.getBriefing(7L);
        assertThat(result.getDomesticValuationAmount()).isNull();
        assertThat(result.getValuationProfit()).isNull();
        assertThat(result.getValuationReturnRate()).isNull();
        assertThat(result.getValuationPriceFrom()).isNull();
        assertThat(result.getValuationPriceTo()).isNull();
        assertThat(result.getInvestmentCostAmount()).isEqualByComparingTo("800");
        assertThat(result.getCashAmount()).isEqualByComparingTo("12345");
    }

    static Stream<Quote> invalidQuotes() {
        return Stream.of(
                (Quote) null,
                new Quote(null, NOW),
                new Quote(BigDecimal.ZERO, NOW),
                new Quote(new BigDecimal("-1"), NOW),
                new Quote(BigDecimal.ONE, null));
    }

    @ParameterizedTest
    @MethodSource("invalidQuotes")
    void invalidQuotesAreNotUsedToInventValuations(Quote quote) {
        stubAmounts("100", "2000");
        Map<String, Quote> quotes = new HashMap<>();
        quotes.put("005930", quote);
        when(priceProvider.getPrices(anySet())).thenReturn(quotes);
        var result = service.getBriefing(7L);
        assertThat(result.getDomesticValuationAmount()).isNull();
        assertThat(result.getValuationProfit()).isNull();
        assertThat(result.getValuationReturnRate()).isNull();
    }

    @Test
    void fractionalQuantitiesRetainDecimalPrecisionAndDuplicateTickersAreRequestedOnce() {
        stubAmounts("250", "2000");
        when(mapper.selectHoldings(11L))
                .thenReturn(List.of(holding("005930", "1.5"), holding("005930", "1")));
        when(priceProvider.getPrices(Set.of("005930")))
                .thenReturn(Map.of("005930", new Quote(new BigDecimal("101.25"), NOW)));
        var result = service.getBriefing(7L);
        assertThat(result.getDomesticValuationAmount()).isEqualByComparingTo("253.125");
        assertThat(result.getValuationProfit()).isEqualByComparingTo("3.125");
        assertThat(result.getValuationReturnRate()).isEqualByComparingTo("1.25");
        verify(priceProvider).getPrices(Set.of("005930"));
        verifyNoMoreInteractions(priceProvider);
    }

    @Test
    void rateRoundsHalfUpAndUsesPurchaseCostNotMarketValueAsDenominator() {
        stubAmounts("100", "1000");
        when(priceProvider.getPrices(anySet()))
                .thenReturn(Map.of("005930", new Quote(new BigDecimal("102.345"), NOW)));
        assertThat(service.getBriefing(7L).getValuationReturnRate()).isEqualByComparingTo("2.35");
    }

    @Test
    void zeroPurchaseCostDoesNotDivideByZeroEvenWithAValidMarketPrice() {
        stubAmounts("0", "10");
        when(mapper.selectHoldings(11L)).thenReturn(List.of(holding("005930", "1")));
        when(priceProvider.getPrices(anySet()))
                .thenReturn(Map.of("005930", new Quote(new BigDecimal("50"), NOW)));
        var result = service.getBriefing(7L);
        assertThat(result.getDomesticValuationAmount()).isEqualByComparingTo("50");
        assertThat(result.getValuationProfit()).isEqualByComparingTo("50");
        assertThat(result.getValuationReturnRate()).isNull();
        assertThat(result.getUnavailableFields()).contains("valuationReturnRate");
    }

    static Stream<RuntimeException> expectedPriceFailures() {
        return Stream.of(
                new ResourceAccessException("SECRET_TEST", new java.net.SocketTimeoutException()),
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE, "SECRET_TEST"),
                new AppException(ErrorType.ASSET_PRICE_UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("expectedPriceFailures")
    @ExtendWith(OutputCaptureExtension.class)
    void priceFailuresPreserveBaseBriefingAndDoNotLeakUpstreamDetails(
            RuntimeException error, CapturedOutput output) {
        stubAmounts("800", "2000");
        AssetMonthlyPurchaseDTO month = new AssetMonthlyPurchaseDTO();
        month.setPurchaseYear(2026);
        month.setPurchaseMonth(10);
        month.setBuyAmount(new BigDecimal("800"));
        when(mapper.selectMonthlyPurchases(11L, NOW)).thenReturn(List.of(month));
        when(priceProvider.getPrices(anySet())).thenThrow(error);
        var result = service.getBriefing(7L);
        assertThat(result.getCashAmount()).isEqualByComparingTo("12345");
        assertThat(result.getInvestmentCostAmount()).isEqualByComparingTo("800");
        assertThat(result.getCumulativeInflowAmount()).isEqualByComparingTo("2000");
        assertThat(result.getInvestmentRatio()).isEqualByComparingTo("40");
        assertThat(result.getMonthlyPurchases())
                .singleElement()
                .satisfies(value -> assertThat(value.buyAmount()).isEqualByComparingTo("800"));
        assertThat(result.getCalculatedAt()).isEqualTo(NOW);
        assertThat(result.getDomesticValuationAmount()).isNull();
        assertThat(result.getValuationProfit()).isNull();
        assertThat(result.getValuationReturnRate()).isNull();
        assertThat(result.getValuationPriceFrom()).isNull();
        assertThat(result.getValuationPriceTo()).isNull();
        assertThat(result.getUnavailableFields())
                .containsExactly(
                        "domesticValuationAmount",
                        "valuationProfit",
                        "valuationReturnRate",
                        "totalAssetAmount",
                        "domesticDayChangeAmount",
                        "domesticDayChangeRate",
                        "dayChangeBaseDate",
                        "packageDeviation");
        assertThat(output.getAll()).doesNotContain("SECRET_TEST");
    }

    @Test
    void programmingErrorsAreNotHiddenAsPriceOutages() {
        stubAmounts("100", "2000");
        var error = new IllegalArgumentException("bug");
        when(priceProvider.getPrices(anySet())).thenThrow(error);
        assertThatThrownBy(() -> service.getBriefing(7L)).isSameAs(error);
    }

    @Test
    void unrelatedApplicationErrorsAreNotHiddenAsPriceOutages() {
        stubAmounts("100", "2000");
        var error = new AppException(ErrorType.ACCOUNT_NOT_FOUND);
        when(priceProvider.getPrices(anySet())).thenThrow(error);
        assertThatThrownBy(() -> service.getBriefing(7L)).isSameAs(error);
    }

    @Test
    void totalAssetIsSumOfCashDomesticAndForeignOnlyWhenAllKnown() {
        assertThat(
                        AssetBriefingServiceImpl.totalAssetOf(
                                new BigDecimal("1000"),
                                new BigDecimal("2000"),
                                new BigDecimal("3000")))
                .isEqualByComparingTo("6000");
        assertThat(AssetBriefingServiceImpl.totalAssetOf(null, BigDecimal.ONE, BigDecimal.ONE))
                .isNull();
        assertThat(AssetBriefingServiceImpl.totalAssetOf(BigDecimal.ONE, null, BigDecimal.ONE))
                .isNull();
        assertThat(AssetBriefingServiceImpl.totalAssetOf(BigDecimal.ONE, BigDecimal.ONE, null))
                .isNull();
    }

    private static HeldLotDTO lot(long productId, String ticker, String qty) {
        return HeldLotDTO.builder()
                .foreignProductId(productId)
                .ticker(ticker)
                .market("NASDAQ")
                .currency("USD")
                .currentQty(new BigDecimal(qty))
                .build();
    }

    @Test
    void foreignValuationIsQtyTimesPreviousCloseTimesFxAndFeedsTotalAsset() {
        stubAmounts("0", "5000");
        when(taxMapper.selectHeldLotsByAccountId(11L))
                .thenReturn(List.of(lot(1L, "AAPL", "2"), lot(1L, "AAPL", "1")));
        when(exchangeRateClient.getBaseRateWithDate("USD"))
                .thenReturn(new RateWithDate(new BigDecimal("1400"), null));
        when(kisPriceClient.getPreviousClose("NAS", "AAPL")).thenReturn(new BigDecimal("200"));

        var result = service.getBriefing(7L);

        // 3주 × 200 × 1400 = 840,000. 총자산 = 예수금 12,345 + 국내 0 + 840,000.
        assertThat(result.getForeignHeldValuationAmount()).isEqualByComparingTo("840000");
        assertThat(result.getTotalAssetAmount()).isEqualByComparingTo("852345");
        verify(kisPriceClient, times(1)).getPreviousClose("NAS", "AAPL");
    }

    @Test
    void foreignPriceFailureLeavesForeignAndTotalAssetNull() {
        stubAmounts("0", "5000");
        when(taxMapper.selectHeldLotsByAccountId(11L)).thenReturn(List.of(lot(1L, "AAPL", "2")));
        when(exchangeRateClient.getBaseRateWithDate("USD"))
                .thenReturn(new RateWithDate(new BigDecimal("1400"), null));
        when(kisPriceClient.getPreviousClose(any(), any()))
                .thenThrow(new AppException(ErrorType.KIS_PRICE_NOT_FOUND, "AAPL"));

        var result = service.getBriefing(7L);

        assertThat(result.getForeignHeldValuationAmount()).isNull();
        assertThat(result.getTotalAssetAmount()).isNull();
        assertThat(result.getUnavailableFields())
                .contains("foreignHeldValuationAmount", "totalAssetAmount");
    }

    @Test
    void noForeignLotsMeansZeroForeignValuationNotUnknown() {
        stubAmounts("0", "5000");

        var result = service.getBriefing(7L);

        assertThat(result.getForeignHeldValuationAmount()).isEqualByComparingTo("0");
        assertThat(result.getTotalAssetAmount()).isEqualByComparingTo("12345");
    }

    @Test
    void domesticDayChangeSumsQtyTimesPriceMinusBaseAndRateUsesBaseValue() {
        stubAmounts("1980.75", "5000");
        when(mapper.selectHoldings(11L))
                .thenReturn(List.of(holding("005930", "2"), holding("000660", "3")));
        doReturn(new DomesticQuote("005930", bd("110"), bd("100"), bd("130"), bd("70")))
                .when(kisDomesticPriceClient)
                .getQuote("005930");
        doReturn(new DomesticQuote("000660", bd("90"), bd("100"), bd("130"), bd("70")))
                .when(kisDomesticPriceClient)
                .getQuote("000660");

        var result = service.getBriefing(7L);

        // 2×(110-100) + 3×(90-100) = -10, 기준 평가 500 → -2.00%
        assertThat(result.getDomesticDayChangeAmount()).isEqualByComparingTo("-10");
        assertThat(result.getDomesticDayChangeRate()).isEqualByComparingTo("-2.00");
        assertThat(result.getDayChangeBaseDate()).isNull();
        // 평가금액은 저장 가격, 전일 대비는 KIS 실시간이라 기준이 다름을 응답에서 구분한다.
        assertThat(result.getDayChangePriceSource()).isEqualTo("KIS_LIVE");
        assertThat(result.getUnavailableFields())
                .contains("dayChangeBaseDate")
                .doesNotContain("domesticDayChangeAmount", "domesticDayChangeRate");
    }

    @Test
    void dayChangeIsNullWhenAnyHoldingQuoteIsMissing() {
        stubAmounts("1980.75", "5000");
        when(mapper.selectHoldings(11L))
                .thenReturn(List.of(holding("005930", "2"), holding("000660", "3")));
        doReturn(new DomesticQuote("005930", bd("110"), bd("100"), bd("130"), bd("70")))
                .when(kisDomesticPriceClient)
                .getQuote("005930");
        // 000660은 기본 스텁(조회 실패)

        var result = service.getBriefing(7L);

        assertThat(result.getDomesticDayChangeAmount()).isNull();
        assertThat(result.getDomesticDayChangeRate()).isNull();
        assertThat(result.getDayChangePriceSource()).isNull();
    }

    @Test
    void noHoldingsMeansZeroDayChangeAndUnknownRate() {
        stubAmounts("0", "5000");

        var result = service.getBriefing(7L);

        assertThat(result.getDomesticDayChangeAmount()).isEqualByComparingTo("0");
        assertThat(result.getDomesticDayChangeRate()).isNull();
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
