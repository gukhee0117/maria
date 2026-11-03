package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KkiruCallPlannerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 29, 0, 0);

    @Mock private CommunityPostRepository postRepository;
    @Mock private CommunityCommentRepository commentRepository;
    @Mock private BusinessClockService clock;

    // 범위 밖 판정은 실제 표현 파일로 한다.
    @Spy private KkiruQuestionClassifier questionClassifier = new KkiruQuestionClassifier();

    // 한도(글당 3·하루 20)는 필드 기본값을 그대로 쓴다.
    @InjectMocks private KkiruCallPlanner planner;

    private void usedToday(long posts, long comments) {
        when(clock.now()).thenReturn(NOW);
        when(postRepository.countByCustomerIdAndKkiruStatusIsNotNullAndCreatedAtGreaterThanEqual(
                        7L, DAY_START))
                .thenReturn(posts);
        when(commentRepository.countByCustomerIdAndKkiruStatusIsNotNullAndCreatedAtGreaterThanEqual(
                        7L, DAY_START))
                .thenReturn(comments);
    }

    @Test
    @DisplayName("@끼루가 없으면 아무것도 세지 않고 null이다")
    void noMention() {
        assertThat(planner.plan("그냥 의견입니다", 7L, null)).isNull();

        verifyNoInteractions(postRepository, commentRepository, clock);
    }

    @Test
    @DisplayName("호출이 있고 범위 안이면 PENDING, 범위 밖이면 OUT_OF_SCOPE로 시작한다")
    void startStatus() {
        usedToday(0, 0);

        assertThat(planner.plan("@끼루 공시 있나요", 7L, null)).isEqualTo(KkiruStatus.PENDING);
        assertThat(planner.plan("@끼루 사도 돼요?", 7L, null)).isEqualTo(KkiruStatus.OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("하루 한도: 글+댓글 호출이 20번이면 막고 19번이면 허용한다")
    void dailyLimit() {
        usedToday(15, 4);
        assertThat(planner.plan("@끼루 질문", 7L, null)).isEqualTo(KkiruStatus.PENDING);

        usedToday(15, 5);
        assertThatThrownBy(() -> planner.plan("@끼루 질문", 7L, null))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("글 한도: 글 본문 호출 1번을 포함해 3번이면 막고 2번이면 허용한다")
    void postLimit() {
        usedToday(0, 0);
        when(commentRepository.countByCommunityPostIdAndKkiruStatusIsNotNull(5L)).thenReturn(1L);
        when(postRepository.existsByCommunityPostIdAndKkiruStatusIsNotNull(5L)).thenReturn(true);
        assertThat(planner.plan("@끼루 질문", 7L, 5L)).isEqualTo(KkiruStatus.PENDING);

        when(commentRepository.countByCommunityPostIdAndKkiruStatusIsNotNull(5L)).thenReturn(2L);
        assertThatThrownBy(() -> planner.plan("@끼루 질문", 7L, 5L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("새 글(postId 없음)은 글 한도를 보지 않는다")
    void newPostSkipsPostLimit() {
        usedToday(0, 0);

        planner.plan("@끼루 질문", 7L, null);

        verify(commentRepository, never()).countByCommunityPostIdAndKkiruStatusIsNotNull(anyLong());
        verify(postRepository, never()).existsByCommunityPostIdAndKkiruStatusIsNotNull(any());
    }

    @Test
    @DisplayName("범위 밖 호출도 한도에 포함된다(반복으로 한도를 피하지 못한다)")
    void outOfScopeCountsTowardLimit() {
        usedToday(20, 0);

        assertThatThrownBy(() -> planner.plan("@끼루 사도 돼요?", 7L, null))
                .isInstanceOf(AppException.class);
    }

    private void usedByEveryone(long posts, long comments) {
        when(postRepository.countByKkiruStatusInAndCreatedAtGreaterThanEqual(any(), eq(DAY_START)))
                .thenReturn(posts);
        when(commentRepository.countByKkiruStatusInAndCreatedAtGreaterThanEqual(
                        any(), eq(DAY_START)))
                .thenReturn(comments);
    }

    @Test
    @DisplayName("전체 하루 상한: 글+댓글이 300이면 COMMUNITY_KKIRU_BUSY, 299이면 허용한다")
    void dailyAnswerLimit() {
        usedToday(0, 0);
        usedByEveryone(200, 99);
        assertThat(planner.plan("@끼루 질문", 7L, null)).isEqualTo(KkiruStatus.PENDING);

        usedByEveryone(200, 100);
        assertThatThrownBy(() -> planner.plan("@끼루 질문", 7L, null))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_KKIRU_BUSY));
    }

    @Test
    @DisplayName("전체 상한은 비용이 든 상태 4개만 세고 OUT_OF_SCOPE는 세지 않는다")
    @SuppressWarnings("unchecked")
    void dailyAnswerLimitCountsCostlyStatusesOnly() {
        usedToday(0, 0);
        usedByEveryone(0, 0);

        planner.plan("@끼루 질문", 7L, null);

        ArgumentCaptor<java.util.Collection<KkiruStatus>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(postRepository)
                .countByKkiruStatusInAndCreatedAtGreaterThanEqual(captor.capture(), eq(DAY_START));
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(
                        KkiruStatus.PENDING,
                        KkiruStatus.ANSWERED,
                        KkiruStatus.NO_SOURCE,
                        KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("전체 상한에 걸린 날에도 범위 밖 질문은 안내(OUT_OF_SCOPE)를 받는다(비용이 없다)")
    void outOfScopeIgnoresDailyAnswerLimit() {
        usedToday(0, 0);

        assertThat(planner.plan("@끼루 사도 돼요?", 7L, null)).isEqualTo(KkiruStatus.OUT_OF_SCOPE);
        verify(postRepository, never())
                .countByKkiruStatusInAndCreatedAtGreaterThanEqual(any(), any());
    }
}
