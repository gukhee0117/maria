package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오래된 PENDING 정리. 서버가 재시작되거나 작업이 죽어 PENDING으로 남은 호출을 FAILED로 바꿔 작성자가 다시 물을 수 있게 한다. 업무시계 기준 5분 넘게
 * PENDING인 행 중, 지금 처리 중이라고 {@link KkiruInFlight}에 적힌 호출(실제 5분 안)은 건드리지 않는다. 호출 행에는 실제 시각이 없고
 * created_at은 업무시계라서, "다시 묻기"로 방금 PENDING이 된 행은 이 목록으로만 보호된다. 조건부 UPDATE라 여러 번 실행해도 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "maria.community.kkiru.sweeper-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KkiruPendingSweeper {
    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    // JPQL "not in"은 빈 목록을 받지 못해 어떤 id와도 겹치지 않는 값을 넣는다.
    private static final List<Long> NO_ID = List.of(-1L);

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final KkiruInFlight inFlight;
    private final BusinessClockService clock;

    /**
     * @return FAILED로 바꾼 호출 수
     */
    @Scheduled(fixedDelayString = "${maria.community.kkiru.sweeper-delay-millis:60000}")
    @Transactional(transactionManager = "jpaTransactionManager")
    public int sweep() {
        LocalDateTime cutoff = clock.now().minus(STALE_AFTER);
        int posts = postRepository.failStalePending(cutoff, orNoId(inFlight.postIds(STALE_AFTER)));
        int comments =
                commentRepository.failStalePending(
                        cutoff, orNoId(inFlight.commentIds(STALE_AFTER)));
        if (posts + comments > 0) {
            log.warn("[KKIRU_PENDING_SWEPT] posts={} comments={}", posts, comments);
        }
        return posts + comments;
    }

    private static Collection<Long> orNoId(Set<Long> ids) {
        return ids.isEmpty() ? NO_ID : ids;
    }
}
