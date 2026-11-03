package com.app.maria.domain.community.service;

import com.app.maria.domain.community.entity.KkiruRetryLogEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.repository.KkiruRetryLogRepository;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 글·댓글 본문에 @끼루가 있으면 호출 한도를 확인하고 시작 상태(PENDING 또는 OUT_OF_SCOPE)를 정한다. */
@Component
@RequiredArgsConstructor
public class KkiruCallPlanner {
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final KkiruRetryLogRepository retryLogRepository;
    private final CustomerRepository customerRepository;
    private final KkiruCallGate callGate;
    private final BusinessClockService clock;
    private final KkiruQuestionClassifier questionClassifier;

    // 호출 비용이 든 상태. 범위 밖(OUT_OF_SCOPE)·후속 질문(FOLLOW_UP)은 세지 않는다.
    private static final List<KkiruStatus> COSTLY_STATUSES =
            List.of(
                    KkiruStatus.PENDING,
                    KkiruStatus.ANSWERED,
                    KkiruStatus.NO_SOURCE,
                    KkiruStatus.FAILED);

    // 고객·글 한도에 세는 상태. 남용 방지라 범위 밖도 세고 후속 질문만 뺀다.
    // 상태를 새로 만들면 여기 넣을지 정해야 한다. 글·댓글 모두 이 목록으로 센다.
    private static final List<KkiruStatus> LIMITED_STATUSES =
            List.of(
                    KkiruStatus.PENDING,
                    KkiruStatus.ANSWERED,
                    KkiruStatus.NO_SOURCE,
                    KkiruStatus.OUT_OF_SCOPE,
                    KkiruStatus.FAILED);

    @Value("${maria.community.kkiru.max-calls-per-post:3}")
    private int maxCallsPerPost = 3;

    @Value("${maria.community.kkiru.max-calls-per-day:20}")
    private int maxCallsPerDay = 20;

    /** 커뮤니티 전체가 하루에 쓸 수 있는 답변 수(LLM·외부 호출 비용 상한). */
    @Value("${maria.community.kkiru.daily-answer-limit:300}")
    private int dailyAnswerLimit = 300;

    /** 한 글·댓글을 다시 물을 수 있는 횟수. */
    @Value("${maria.community.kkiru.max-retries-per-row:10}")
    private int maxRetriesPerRow = 10;

    /** 다시 묻기 검사: 글을 고친 뒤라 본문이 아직 호출 조건(멘션 있음, 범위 안)인지 본다. 한도는 처음 호출 때 셌으므로 다시 세지 않는다. */
    public boolean canRetry(String content) {
        return KkiruMention.in(content) && !questionClassifier.isOutOfScope(content);
    }

    /**
     * 다시 묻기 검사와 예약. 재시도도 새 호출이라 하루 예산(고객별·전체)을 쓴다: 오늘 만든 행 수에 오늘 한 재시도 수(`kiru_retry_log`)를 더해 한도와
     * 비교하고, 통과하면 재시도 한 줄을 기록한다. 그래서 며칠 전에 만든 FAILED 행을 다시 물어도 오늘 예산에서 차감된다. 한 행은 {@code
     * maxRetriesPerRow}번까지만 다시 물을 수 있다. 호출하는 트랜잭션은 READ_COMMITTED여야 한다({@link #plan} 참고).
     */
    public void requireRetryLimits(Long customerId, int retryCount) {
        if (retryCount >= maxRetriesPerRow) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED, customerId);
        }
        lockCustomer(customerId);
        LocalDateTime now = clock.now();
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        long retriesToday =
                retryLogRepository.countByCustomerIdAndRetriedAtGreaterThanEqual(
                        customerId, dayStart);
        if (usedToday(customerId, dayStart) + retriesToday >= maxCallsPerDay) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED, customerId);
        }
        callGate.lockDailyLimit();
        long allRetriesToday = retryLogRepository.countByRetriedAtGreaterThanEqual(dayStart);
        if (usedByEveryone(dayStart) + allRetriesToday >= dailyAnswerLimit) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_BUSY);
        }
        retryLogRepository.save(KkiruRetryLogEntity.of(customerId, now));
    }

    /**
     * 한도 확인과 호출 예약(PENDING 저장)이 원자적이도록 고객 행과 전체 잠금 행을 잠근다. lock 순서는 글 → 고객 → 전체로 고정한다. 호출하는 트랜잭션은
     * READ_COMMITTED여야 한다: REPEATABLE READ면 잠금 전에 실행된 조회의 snapshot으로 아래 count를 읽어 앞 요청이 커밋한 호출을 못 볼
     * 수 있다.
     *
     * @param postId 댓글이면 그 글의 id, 새 글이면 null
     * @return 호출이 없으면 null
     */
    public KkiruStatus plan(String content, Long customerId, Long postId) {
        if (!KkiruMention.in(content)) {
            return null;
        }
        // 같은 고객의 호출을 한 번에 하나씩: 서로 다른 글에 동시에 불러 하루 한도를 넘기지 못하게 한다.
        lockCustomer(customerId);
        LocalDateTime dayStart = clock.now().toLocalDate().atStartOfDay();
        if (usedToday(customerId, dayStart) >= maxCallsPerDay) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED, customerId);
        }
        if (postId != null) {
            long usedOnPost =
                    commentRepository.countByCommunityPostIdAndKkiruStatusIn(
                                    postId, LIMITED_STATUSES)
                            + (postRepository.existsByCommunityPostIdAndKkiruStatusIn(
                                            postId, LIMITED_STATUSES)
                                    ? 1
                                    : 0);
            if (usedOnPost >= maxCallsPerPost) {
                throw new AppException(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED, postId);
            }
        }
        // 범위 밖 질문은 비용이 없어서(답을 만들지 않음) 전체 상한에도 걸리지 않고 안내를 받는다.
        if (questionClassifier.isOutOfScope(content)) {
            return KkiruStatus.OUT_OF_SCOPE;
        }
        // 전체 상한도 확인과 예약이 순서대로 일어나게 잠근다. 범위 밖 질문은 전체 상한을 쓰지 않아 잠그지 않는다.
        callGate.lockDailyLimit();
        if (usedByEveryone(dayStart) >= dailyAnswerLimit) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_BUSY);
        }
        return KkiruStatus.PENDING;
    }

    private void lockCustomer(Long customerId) {
        customerRepository
                .findByIdForUpdate(customerId)
                .orElseThrow(() -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
    }

    /** 이 고객이 오늘 쓴 호출 수(글+댓글, 후속 질문 제외). */
    private long usedToday(Long customerId, LocalDateTime dayStart) {
        return postRepository.countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
                        customerId, LIMITED_STATUSES, dayStart)
                + commentRepository.countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
                        customerId, LIMITED_STATUSES, dayStart);
    }

    /** 커뮤니티 전체가 오늘 쓴 비용 있는 호출 수(글+댓글). */
    private long usedByEveryone(LocalDateTime dayStart) {
        return postRepository.countByKkiruStatusInAndCreatedAtGreaterThanEqual(
                        COSTLY_STATUSES, dayStart)
                + commentRepository.countByKkiruStatusInAndCreatedAtGreaterThanEqual(
                        COSTLY_STATUSES, dayStart);
    }
}
