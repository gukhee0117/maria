package com.app.maria.domain.settlement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.settlement.provider.ExchangeRateProviderImpl;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.securities.SecuritiesMarketClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.RestTemplateConfig;
import com.app.maria.global.config.properties.ExchangeApiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(SpringExtension.class)
@Import({
    RestTemplateConfig.class,
    ExchangeRestTemplateConfig.class,
    ExchangeApiProperties.class,
    ExchangeRateClient.class,
    ExchangeRateProviderImpl.class
})
class ExchangeRestTemplateConfigTest {

    @MockitoBean private BusinessClockService businessClockService;

    @MockitoBean private SecuritiesMarketClient securitiesMarketClient;

    @Autowired private RestTemplate defaultRestTemplate;

    @Autowired
    @Qualifier("settlementRestTemplate")
    private RestTemplate settlementRestTemplate;

    @Autowired private ExchangeRateClient defaultExchangeRateClient;

    @Autowired
    @Qualifier("settlementExchangeRateClient")
    private ExchangeRateClient settlementExchangeRateClient;

    @Autowired private ExchangeRateProviderImpl exchangeRateProvider;

    @Test
    @DisplayName("기본 Client와 Settlement 전용 Client는 서로 다른 RestTemplate을 사용한다")
    void separatesDefaultAndSettlementClients() {
        assertThat(defaultRestTemplate).isNotSameAs(settlementRestTemplate);
        assertThat(defaultExchangeRateClient).isNotSameAs(settlementExchangeRateClient);

        assertThat(ReflectionTestUtils.getField(defaultExchangeRateClient, "restTemplate"))
                .isSameAs(defaultRestTemplate);
        assertThat(ReflectionTestUtils.getField(settlementExchangeRateClient, "restTemplate"))
                .isSameAs(settlementRestTemplate);
    }

    @Test
    @DisplayName("Settlement Provider에는 Settlement 전용 환율 Client가 주입된다")
    void injectsSettlementClientIntoProvider() {
        assertThat(ReflectionTestUtils.getField(exchangeRateProvider, "exchangeRateClient"))
                .isSameAs(settlementExchangeRateClient);
    }

    @Test
    @DisplayName("기본 RestTemplate과 Settlement RestTemplate 둘 다 연결 3초, 응답 5초 제한을 적용한다")
    void appliesSameTimeoutToBothRestTemplates() {
        SimpleClientHttpRequestFactory defaultFactory =
                (SimpleClientHttpRequestFactory) defaultRestTemplate.getRequestFactory();
        SimpleClientHttpRequestFactory settlementFactory =
                (SimpleClientHttpRequestFactory) settlementRestTemplate.getRequestFactory();

        assertThat(ReflectionTestUtils.getField(defaultFactory, "connectTimeout")).isEqualTo(3_000);
        assertThat(ReflectionTestUtils.getField(defaultFactory, "readTimeout")).isEqualTo(5_000);
        assertThat(ReflectionTestUtils.getField(settlementFactory, "connectTimeout"))
                .isEqualTo(3_000);
        assertThat(ReflectionTestUtils.getField(settlementFactory, "readTimeout")).isEqualTo(5_000);
    }
}
