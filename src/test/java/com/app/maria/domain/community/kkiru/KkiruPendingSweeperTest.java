package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({
    JpaConfig.class,
    TransactionConfig.class,
    KkiruPendingSweeper.class,
    KkiruInFlight.class,
    KkiruPendingSweeperTest.MovableClockBean.class
})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KkiruPendingSweeperTest {
    // 업무시계 15:00 → 정리 기준(cutoff)은 14:55
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);
    private static final LocalDateTime OLD = NOW.minusMinutes(10);
    private static final LocalDateTime RECENT = NOW.minusMinutes(2);

    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T01:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class MovableClockBean {
        @Bean
        MovableClock clock() {
            return new MovableClock();
        }
    }

    @Autowired private KkiruPendingSweeper sweeper;
    @Autowired private KkiruInFlight inFlight;
    @Autowired private MovableClock clock;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private BusinessClockService businessClock;

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
        when(businessClock.now()).thenReturn(NOW);
    }

    private CommunityPostEntity post(KkiruStatus status, LocalDateTime createdAt) {
        return postRepository.saveAndFlush(
                CommunityPostEntity.write(1L, "000660", "@끼루 질문", status, createdAt));
    }

    private CommunityCommentEntity comment(
            CommunityPostEntity post, KkiruStatus status, LocalDateTime createdAt) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), null, 2L, "@끼루 질문", status, createdAt));
    }

    private KkiruStatus postStatus(CommunityPostEntity post) {
        entityManager.clear();
        return postRepository.findById(post.getCommunityPostId()).orElseThrow().getKkiruStatus();
    }

    private KkiruStatus commentStatus(CommunityCommentEntity comment) {
        entityManager.clear();
        return commentRepository
                .findById(comment.getCommunityCommentId())
                .orElseThrow()
                .getKkiruStatus();
    }

    @Test
    @DisplayName("5분 넘게 PENDING인 글·댓글은 FAILED로, 5분이 안 된 PENDING과 다른 상태는 그대로 둔다")
    void sweepsOnlyOldPending() {
        CommunityPostEntity oldPost = post(KkiruStatus.PENDING, OLD);
        CommunityPostEntity recentPost = post(KkiruStatus.PENDING, RECENT);
        CommunityPostEntity answered = post(KkiruStatus.ANSWERED, OLD);
        CommunityPostEntity noCall = post(null, OLD);
        CommunityCommentEntity oldComment = comment(oldPost, KkiruStatus.PENDING, OLD);
        CommunityCommentEntity recentComment = comment(oldPost, KkiruStatus.PENDING, RECENT);
        CommunityCommentEntity noSource = comment(oldPost, KkiruStatus.NO_SOURCE, OLD);

        assertThat(sweeper.sweep()).isEqualTo(2);

        assertThat(postStatus(oldPost)).isEqualTo(KkiruStatus.FAILED);
        assertThat(postStatus(recentPost)).isEqualTo(KkiruStatus.PENDING);
        assertThat(postStatus(answered)).isEqualTo(KkiruStatus.ANSWERED);
        assertThat(postStatus(noCall)).isNull();
        assertThat(commentStatus(oldComment)).isEqualTo(KkiruStatus.FAILED);
        assertThat(commentStatus(recentComment)).isEqualTo(KkiruStatus.PENDING);
        assertThat(commentStatus(noSource)).isEqualTo(KkiruStatus.NO_SOURCE);
    }

    @Test
    @DisplayName("경계: 정확히 5분 된 PENDING은 두고, 1초 더 지나면 정리한다")
    void boundary() {
        CommunityPostEntity exactly = post(KkiruStatus.PENDING, NOW.minusMinutes(5));
        CommunityPostEntity over = post(KkiruStatus.PENDING, NOW.minusMinutes(5).minusSeconds(1));

        sweeper.sweep();

        assertThat(postStatus(exactly)).isEqualTo(KkiruStatus.PENDING);
        assertThat(postStatus(over)).isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("지금 처리 중인 호출(실제 5분 안)은 created_at이 오래돼도 정리하지 않는다(다시 묻기 직후 보호)")
    void inFlightIsProtected() {
        CommunityPostEntity running = post(KkiruStatus.PENDING, OLD);
        CommunityPostEntity idle = post(KkiruStatus.PENDING, OLD);
        CommunityCommentEntity runningComment = comment(idle, KkiruStatus.PENDING, OLD);
        CommunityCommentEntity idleComment = comment(idle, KkiruStatus.PENDING, OLD);
        inFlight.start(new KkiruInFlight.CallKey(running.getCommunityPostId(), null));
        inFlight.start(
                new KkiruInFlight.CallKey(
                        idle.getCommunityPostId(), runningComment.getCommunityCommentId()));

        sweeper.sweep();

        assertThat(postStatus(running)).isEqualTo(KkiruStatus.PENDING);
        assertThat(commentStatus(runningComment)).isEqualTo(KkiruStatus.PENDING);
        assertThat(postStatus(idle)).isEqualTo(KkiruStatus.FAILED);
        assertThat(commentStatus(idleComment)).isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("진행 중 목록에 있어도 실제 5분이 지났으면 죽은 작업으로 보고 정리한다")
    void staleInFlightIsSwept() {
        CommunityPostEntity stuck = post(KkiruStatus.PENDING, OLD);
        inFlight.start(new KkiruInFlight.CallKey(stuck.getCommunityPostId(), null));
        clock.advance(Duration.ofMinutes(6));

        sweeper.sweep();

        assertThat(postStatus(stuck)).isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("여러 번 돌려도 안전하다(두 번째는 0건)")
    void idempotent() {
        post(KkiruStatus.PENDING, OLD);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(sweeper.sweep()).isZero();
    }
}
