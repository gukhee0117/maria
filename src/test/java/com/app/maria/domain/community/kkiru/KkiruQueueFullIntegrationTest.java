package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.service.KkiruCallRequestedEvent;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.notification.service.CustomerNotificationService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설계 8.5의 가정을 진짜 트랜잭션 커밋으로 확인한다. (1) 커밋 뒤 콜백의 예외가 글 저장 요청 스레드로 번지는가 — spring-tx 6.2.19에서는 번지지
 * 않는다(afterCompletion에서 로그만 남고 삼켜진다). 그래서 거절된 호출은 리스너가 직접 잡아 FAILED로 끝내지 않으면 PENDING으로 남는다. (2) 그
 * FAILED 저장이 새 트랜잭션으로 되는가. 테스트 자체는 트랜잭션 밖에서 돌아야 커밋 뒤 콜백이 실제로 불린다.
 */
@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({
    JpaConfig.class,
    TransactionConfig.class,
    KkiruCallListener.class,
    KkiruReplyWriter.class,
    KkiruReplyComposer.class,
    KkiruInFlight.class,
    KkiruQueueFullIntegrationTest.Beans.class,
    KkiruQueueFullIntegrationTest.ThrowingAfterCommitListener.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KkiruQueueFullIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    record ToyEvent() {}

    @Component
    static class ThrowingAfterCommitListener {
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(ToyEvent event) {
            throw new IllegalStateException("커밋 뒤 콜백 예외");
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        /** 스레드 1개·대기열 0: 하나가 자리를 막고 있으면 다음 제출은 AbortPolicy로 거절된다. */
        @Bean("communityKkiruTaskExecutor")
        ThreadPoolTaskExecutor communityKkiruTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(0);
            return executor;
        }
    }

    @Autowired
    @Qualifier("jpaTransactionManager")
    private PlatformTransactionManager txManager;

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private KkiruInFlight inFlight;

    @Autowired
    @Qualifier("communityKkiruTaskExecutor")
    private Executor executor;

    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CustomerNotificationService notificationService;

    @MockitoBean private KkiruReplyService replyService;
    @MockitoBean private BusinessClockService clock;

    // PER_CLASS 인스턴스를 테스트마다 다시 쓰므로 래치도 매번 새로 만든다.
    private CountDownLatch release;

    @BeforeAll
    void createSchema() {
        CommunityTestSchema.create(jdbc);
    }

    @AfterAll
    void dropSchema() {
        CommunityTestSchema.drop(jdbc);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM community_post");
        when(clock.now()).thenReturn(NOW);
        // 유일한 스레드를 막아 둔다. 앞 테스트의 막이가 아직 풀려나는 중일 수 있어, 자리가 날 때까지 기다린다.
        release = new CountDownLatch(1);
        waitUntilIdle();
        executor.execute(
                () -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    private void waitUntilIdle() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (pool.getActiveCount() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        waitUntilIdle();
    }

    private String statusOf(long postId) {
        return jdbc.queryForObject(
                "SELECT kiru_status FROM community_post WHERE community_post_id = ?",
                String.class,
                postId);
    }

    @Test
    @DisplayName("가정 1 확인(설계와 다름): 커밋 뒤 콜백의 예외는 글 저장 요청 스레드로 번지지 않고 삼켜진다")
    void afterCommitExceptionDoesNotPropagate() {
        TransactionTemplate template = new TransactionTemplate(txManager);

        assertThatCode(
                        () ->
                                template.executeWithoutResult(
                                        s -> publisher.publishEvent(new ToyEvent())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("가정 2 증명: 큐가 가득 차 실행기가 거절해도 글 저장은 예외 없이 끝나고, 호출은 새 트랜잭션으로 FAILED가 저장된다")
    void queueFullDoesNotBreakPostSave() {
        TransactionTemplate template = new TransactionTemplate(txManager);

        Long postId =
                template.execute(
                        status -> {
                            CommunityPostEntity post =
                                    postRepository.save(
                                            CommunityPostEntity.write(
                                                    1L,
                                                    "000660",
                                                    "@끼루 질문",
                                                    KkiruStatus.PENDING,
                                                    NOW));
                            publisher.publishEvent(
                                    new KkiruCallRequestedEvent(post.getCommunityPostId(), null));
                            return post.getCommunityPostId();
                        });

        assertThat(postId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_post", Long.class))
                .isEqualTo(1L); // 글은 저장됐다
        assertThat(statusOf(postId)).isEqualTo("FAILED");
        verify(replyService, never()).reply(postId, null);
        assertThat(inFlight.postIds(Duration.ofHours(1))).isEmpty();
    }
}
