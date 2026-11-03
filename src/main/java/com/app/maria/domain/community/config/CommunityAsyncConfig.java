package com.app.maria.domain.community.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 끼루 답변 전용 실행기. 이름 없는 @Async는 스레드 수 제한이 없는 기본 실행기로 가므로 이름을 붙여 따로 둔다. 대기열이 가득 차면 기본
 * 정책(AbortPolicy)대로 거절하고, 거절은 {@code KkiruCallListener}가 받아 그 호출을 FAILED로 끝낸다(요청 스레드가 LLM·외부 호출을 직접
 * 하지 않는다).
 */
@Configuration
@EnableAsync
public class CommunityAsyncConfig {
    @Bean(name = "communityKkiruTaskExecutor")
    public Executor communityKkiruTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("community-kkiru-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        return executor;
    }

    /** 도구·LLM 호출용. 답 작업(communityKkiruTaskExecutor)이 기다리는 동안 막히지 않게 풀을 따로 둔다. */
    @Bean(name = "communityKkiruToolExecutor")
    public Executor communityKkiruToolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("community-kkiru-tool-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        return executor;
    }

    /** 화제 종목 이유 생성용. 끼루 답변의 도구 실행기와 나눠, 순위 조회가 몰려도 끼루 답변이 밀리지 않게 한다. */
    @Bean(name = "communityTrendReasonExecutor")
    public Executor communityTrendReasonExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("community-trend-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        return executor;
    }
}
