package com.app.maria.domain.community.service;

import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.KkiruStatus;
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
    private final BusinessClockService clock;
    private final KkiruQuestionClassifier questionClassifier;

    // 호출 비용이 든 상태. 범위 밖(OUT_OF_SCOPE)은 세지 않는다.
    private static final List<KkiruStatus> COSTLY_STATUSES =
            List.of(
                    KkiruStatus.PENDING,
                    KkiruStatus.ANSWERED,
                    KkiruStatus.NO_SOURCE,
                    KkiruStatus.FAILED);

    @Value("${maria.community.kkiru.max-calls-per-post:3}")
    private int maxCallsPerPost = 3;

    @Value("${maria.community.kkiru.max-calls-per-day:20}")
    private int maxCallsPerDay = 20;

    /** 커뮤니티 전체가 하루에 쓸 수 있는 답변 수(LLM·외부 호출 비용 상한). */
    @Value("${maria.community.kkiru.daily-answer-limit:300}")
    private int dailyAnswerLimit = 300;

    /**
     * @param postId 댓글이면 그 글의 id, 새 글이면 null
     * @return 호출이 없으면 null
     */
    public KkiruStatus plan(String content, Long customerId, Long postId) {
        if (!KkiruMention.in(content)) {
            return null;
        }
        LocalDateTime dayStart = clock.now().toLocalDate().atStartOfDay();
        long usedToday =
                postRepository.countByCustomerIdAndKkiruStatusIsNotNullAndCreatedAtGreaterThanEqual(
                                customerId, dayStart)
                        + commentRepository
                                .countByCustomerIdAndKkiruStatusIsNotNullAndCreatedAtGreaterThanEqual(
                                        customerId, dayStart);
        if (usedToday >= maxCallsPerDay) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED, customerId);
        }
        if (postId != null) {
            long usedOnPost =
                    commentRepository.countByCommunityPostIdAndKkiruStatusIsNotNull(postId)
                            + (postRepository.existsByCommunityPostIdAndKkiruStatusIsNotNull(postId)
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
        long usedByEveryone =
                postRepository.countByKkiruStatusInAndCreatedAtGreaterThanEqual(
                                COSTLY_STATUSES, dayStart)
                        + commentRepository.countByKkiruStatusInAndCreatedAtGreaterThanEqual(
                                COSTLY_STATUSES, dayStart);
        if (usedByEveryone >= dailyAnswerLimit) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_BUSY);
        }
        return KkiruStatus.PENDING;
    }
}
