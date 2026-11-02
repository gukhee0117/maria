package com.app.maria.domain.asset.config;

import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.domain.asset.price.UnavailableAssetPriceProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 사용자 정의 연동 빈을 먼저 등록한 뒤, 없을 때만 기본 빈을 제공한다. */
@AutoConfiguration
public class AssetPriceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AssetPriceProvider.class)
    public AssetPriceProvider unavailableAssetPriceProvider() {
        return new UnavailableAssetPriceProvider();
    }
}
