package com.app.maria.global.client.securities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.exchange.RateWithDate;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.KisDomesticPriceClient;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.client.kis.KisTokenService;
import com.app.maria.global.client.kis.PriceWithDate;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExchangeApiProperties;
import com.app.maria.global.config.properties.PriceApiProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** 소스가 securities일 때 세 클라이언트가 KIS·환율 API(RestTemplate)를 한 번도 부르지 않고 증권사 값을 돌려준다. */
class MarketSourceDelegationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);

    RestTemplate restTemplate = mock(RestTemplate.class);
    KisTokenService token = mock(KisTokenService.class);
    BusinessClockService clock = mock(BusinessClockService.class);
    SecuritiesMarketClient securities = mock(SecuritiesMarketClient.class);

    @BeforeEach
    void setUp() {
        when(securities.isActive()).thenReturn(true);
        when(securities.businessDate()).thenReturn(DAY);
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 4, 10, 12, 0));
    }

    @Test
    void 해외_전일종가는_업무날짜로_증권사에서() {
        when(securities.foreignPreviousClose("QQQ", DAY))
                .thenReturn(new PriceWithDate(new BigDecimal("570.3"), DAY.minusDays(1)));
        var client = new KisPriceClient(restTemplate, token, new PriceApiProperties(), securities);
        assertThat(client.getPreviousClose("NAS", "QQQ")).isEqualByComparingTo("570.3");
        assertThat(client.getPreviousCloseWithDate("NAS", "QQQ").getBaseDate())
                .isEqualTo(DAY.minusDays(1));
        verifyNoInteractions(restTemplate, token);
    }

    @Test
    void 국내_시세는_getQuotes_한번에_증권사에서() {
        var q =
                new DomesticQuote(
                        "005930", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        when(securities.domesticQuotes(List.of("005930", "000660"), DAY))
                .thenReturn(Map.of("005930", q));
        when(securities.domesticQuotes(List.of("005930"), DAY)).thenReturn(Map.of("005930", q));
        var client =
                new KisDomesticPriceClient(
                        restTemplate, token, new PriceApiProperties(), securities);
        assertThat(client.getQuotes(List.of("005930", "000660"))).containsOnlyKeys("005930");
        assertThat(client.getQuote("005930")).isEqualTo(q);
        verifyNoInteractions(restTemplate, token);
    }

    @Test
    void 환율은_증권사_값을_그대로_쓰고_확정산은_finalRate() {
        when(securities.baseRate("JPY", DAY))
                .thenReturn(new RateWithDate(new BigDecimal("9.3474"), DAY));
        when(securities.finalRate("JPY", DAY)).thenReturn(new BigDecimal("9.4"));
        var client =
                new ExchangeRateClient(
                        restTemplate, new ExchangeApiProperties(), clock, securities);
        assertThat(client.getBaseRate("JPY", DAY)).isEqualByComparingTo("9.3474");
        assertThat(client.getBaseRate("JPY")).isEqualByComparingTo("9.3474");
        assertThat(client.getBaseRateWithDate("JPY").getBaseDate()).isEqualTo(DAY);
        assertThat(client.getFinalRate("JPY", DAY)).isEqualByComparingTo("9.4");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void 소스가_kis면_증권사를_부르지_않고_getFinalRate는_getBaseRate와_같다() {
        SecuritiesMarketClient off = mock(SecuritiesMarketClient.class);
        when(off.isActive()).thenReturn(false);
        var props = new ExchangeApiProperties();
        props.setUrl("http://exchange.test/x?");
        props.setApiKey("k");
        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .valueToTree(
                                        List.of(
                                                Map.of(
                                                        "cur_unit",
                                                        "USD",
                                                        "deal_bas_r",
                                                        "1,400.5"))));
        var client = new ExchangeRateClient(restTemplate, props, clock, off);
        assertThat(client.getFinalRate("USD", DAY)).isEqualByComparingTo("1400.5");
        verify(restTemplate).getForObject(anyString(), any());
        verify(off, org.mockito.Mockito.never()).baseRate(anyString(), any());
    }
}
