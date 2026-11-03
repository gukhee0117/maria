package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.app.maria.domain.community.service.KkiruCallRequestedEvent;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class KkiruCallListenerTest {
    private static final Duration ANY_AGE = Duration.ofHours(1);

    @Mock private KkiruReplyService replyService;
    @Mock private KkiruReplyWriter replyWriter;
    private final KkiruInFlight inFlight = new KkiruInFlight(Clock.systemUTC());
    private KkiruCallListener listener;

    @BeforeEach
    void setUp() {
        Executor direct = Runnable::run;
        listener = new KkiruCallListener(replyService, replyWriter, inFlight, direct);
    }

    private KkiruCallListener rejectingListener() {
        Executor full =
                command -> {
                    throw new RejectedExecutionException("queue full");
                };
        return new KkiruCallListener(replyService, replyWriter, inFlight, full);
    }

    @Test
    @DisplayName("이벤트의 글·댓글 번호로 답변 처리를 부른다")
    void delegates() {
        listener.onKkiruCallRequested(new KkiruCallRequestedEvent(11L, 31L));
        listener.onKkiruCallRequested(new KkiruCallRequestedEvent(12L, null));

        verify(replyService).reply(11L, 31L);
        verify(replyService).reply(12L, null);
    }

    @Test
    @DisplayName("처리하는 동안은 진행 중 목록에 있고, 끝나면 빠진다")
    void inFlightWhileRunning() {
        doAnswer(
                        invocation -> {
                            assertThat(inFlight.commentIds(ANY_AGE)).containsExactly(31L);
                            return null;
                        })
                .when(replyService)
                .reply(11L, 31L);

        listener.onKkiruCallRequested(new KkiruCallRequestedEvent(11L, 31L));

        assertThat(inFlight.commentIds(ANY_AGE)).isEmpty();
    }

    @Test
    @DisplayName("처리 중 예외가 나도 진행 중 목록에서 빠진다")
    void inFlightRemovedOnFailure() {
        doThrow(new IllegalStateException("boom")).when(replyService).reply(12L, null);

        assertThatThrownBy(
                        () -> listener.onKkiruCallRequested(new KkiruCallRequestedEvent(12L, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(inFlight.postIds(ANY_AGE)).isEmpty();
    }

    @Test
    @DisplayName("실행기가 거절하면 예외 없이 그 호출만 새 트랜잭션으로 FAILED 처리하고 처리는 시작하지 않는다")
    void queueFull() {
        assertThatCode(
                        () ->
                                rejectingListener()
                                        .onKkiruCallRequested(
                                                new KkiruCallRequestedEvent(11L, 31L)))
                .doesNotThrowAnyException();

        verify(replyWriter).markFailedInNewTransaction(11L, 31L);
        verify(replyService, never()).reply(11L, 31L);
        assertThat(inFlight.commentIds(ANY_AGE)).isEmpty();
    }

    @Test
    @DisplayName("거절된 호출의 FAILED 저장까지 실패해도 요청 스레드로 예외를 던지지 않는다")
    void queueFullAndMarkFails() {
        doThrow(new IllegalStateException("DB down"))
                .when(replyWriter)
                .markFailedInNewTransaction(12L, null);

        assertThatCode(
                        () ->
                                rejectingListener()
                                        .onKkiruCallRequested(
                                                new KkiruCallRequestedEvent(12L, null)))
                .doesNotThrowAnyException();
        assertThat(inFlight.postIds(ANY_AGE)).isEmpty();
    }

    @Test
    @DisplayName("커밋 뒤에 돌고, @Async는 쓰지 않는다(거절 예외를 직접 잡으려고. 어노테이션이 바뀌면 실패)")
    void runsAfterCommitWithoutAsync() throws Exception {
        Method method =
                KkiruCallListener.class.getMethod(
                        "onKkiruCallRequested", KkiruCallRequestedEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class)).isNull();
    }
}
