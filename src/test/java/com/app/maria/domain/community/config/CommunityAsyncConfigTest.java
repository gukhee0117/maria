package com.app.maria.domain.community.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class CommunityAsyncConfigTest {
    private final CommunityAsyncConfig config = new CommunityAsyncConfig();

    @Test
    @DisplayName("답 작업 실행기는 가득 차면 거절한다(요청 스레드가 직접 처리하지 않는다 — CallerRuns 금지)")
    void taskExecutorRejectsWhenFull() {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) config.communityKkiruTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("도구 실행기는 답 작업 실행기와 다른 풀이다")
    void toolExecutorIsSeparate() {
        assertThat(config.communityKkiruToolExecutor())
                .isNotSameAs(config.communityKkiruTaskExecutor());
    }
}
