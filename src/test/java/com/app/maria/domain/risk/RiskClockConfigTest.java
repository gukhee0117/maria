package com.app.maria.domain.risk;

import static org.assertj.core.api.Assertions.*;

import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.config.RiskConfig;
import com.app.maria.global.config.ClockConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class RiskClockConfigTest {
    @Test
    void addingRiskWrapperDoesNotCreateAmbiguousLoginClockBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClockConfig.class, RiskConfig.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(Clock.class)
                                    .hasSingleBean(RiskMarketClock.class);
                            assertThat(context.getBean(RiskMarketClock.class).now().getZone())
                                    .isEqualTo(RiskMarketClock.ZONE);
                        });
    }
}
