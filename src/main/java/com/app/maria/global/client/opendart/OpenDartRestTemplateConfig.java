package com.app.maria.global.client.opendart;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * OpenDART 전용 RestTemplate 둘. 기존 공용 빈(읽기 5초)은 3.6MB ZIP을 받기에 짧아서 대응표 다운로드는 읽기 30초짜리를 따로 쓴다. 공용 빈이
 * {@code @Primary}라 다른 곳의 주입에는 영향이 없다.
 */
@Configuration
public class OpenDartRestTemplateConfig {

    @Bean("openDartRestTemplate")
    public RestTemplate openDartRestTemplate() {
        return create(Duration.ofSeconds(8));
    }

    @Bean("openDartDownloadRestTemplate")
    public RestTemplate openDartDownloadRestTemplate() {
        return create(Duration.ofSeconds(30));
    }

    private static RestTemplate create(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}
