package com.app.maria.domain.customer;

import com.app.maria.domain.customer.service.CiHasher;
import com.app.maria.global.config.properties.CiProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 고객 인증 서비스 테스트용 빈. 시계는 고정(2026-10-25 12:00 KST)이다. */
@TestConfiguration
public class CustomerAuthTestBeans {
    public static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    @Bean
    public Clock clock() {
        return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    }

    @Bean
    public CiProperties ciProperties() {
        CiProperties properties = new CiProperties();
        properties.setPepper("test-pepper");
        return properties;
    }

    @Bean
    public CiHasher ciHasher(CiProperties properties) {
        return new CiHasher(properties);
    }
}
