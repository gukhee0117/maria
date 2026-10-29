package com.app.maria.domain.risk.config;

import com.app.maria.domain.risk.clock.RiskMarketClock;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfig {
    @Bean
    public RiskMarketClock riskMarketClock() {
        // A wrapper avoids adding a second java.time.Clock bean used by login.
        return new RiskMarketClock(Clock.system(RiskMarketClock.ZONE));
    }

    @Bean("pythonRiskRestClient")
    public RestClient pythonRiskRestClient(RestClient.Builder builder, RiskProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return builder.baseUrl(properties.getUrl()).requestFactory(factory).build();
    }
}
