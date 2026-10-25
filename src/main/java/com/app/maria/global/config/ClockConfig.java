package com.app.maria.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실제 시각이 필요한 곳(가입일시·마지막 로그인)에 주입하는 시계. 업무시계(BusinessClockService)와 다르다. */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
