package com.app.maria.domain.asset.price;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.asset.config.AssetPriceAutoConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class AssetPriceAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AssetPriceAutoConfiguration.class));

    @Test
    void createsOneUnavailableProviderWhenNoIntegrationExists() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AssetPriceProvider.class);
                    assertThat(context.getBean(AssetPriceProvider.class))
                            .isInstanceOf(UnavailableAssetPriceProvider.class);
                });
    }

    @Test
    void realIntegrationBeanReplacesFallbackWithoutAmbiguousInjection() {
        contextRunner
                .withUserConfiguration(Integration.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(AssetPriceProvider.class);
                            assertThat(context).doesNotHaveBean("unavailableAssetPriceProvider");
                            assertThat(context.getBean(AssetPriceProvider.class))
                                    .isSameAs(context.getBean("integrationPriceProvider"));
                        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Integration {
        @Bean
        AssetPriceProvider integrationPriceProvider() {
            return tickers -> Map.of();
        }
    }
}
