package com.app.maria.domain.asset.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AssetLiveConfiguration {
    @Bean("assetPriceRestClient")
    public RestClient assetPriceRestClient(
            RestClient.Builder builder, @Value("${custom.returns-security.url}") String url) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(3));
        return builder.baseUrl(url).requestFactory(requests).build();
    }
}
