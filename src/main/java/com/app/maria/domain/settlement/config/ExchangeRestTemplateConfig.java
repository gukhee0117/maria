package com.app.maria.domain.settlement.config;

import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.securities.SecuritiesMarketClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExchangeApiProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ExchangeRestTemplateConfig {
    @Bean("settlementRestTemplate")
    public RestTemplate settlementRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        return new RestTemplate(requestFactory);
    }

    @Bean("settlementExchangeRateClient")
    public ExchangeRateClient settlementExchangeRateClient(
            @Qualifier("settlementRestTemplate") RestTemplate restTemplate,
            ExchangeApiProperties exchangeApiProperties,
            BusinessClockService businessClockService,
            SecuritiesMarketClient securities) {
        return new ExchangeRateClient(
                restTemplate, exchangeApiProperties, businessClockService, securities);
    }
}
