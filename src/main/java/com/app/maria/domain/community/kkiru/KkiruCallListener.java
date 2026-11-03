package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.service.KkiruCallRequestedEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 글·댓글 저장이 커밋된 뒤에 끼루 답변을 따로 만든다. 커밋 전에 시작하면 비동기 처리가 아직 저장되지 않은 글을 못 찾는다. 글 저장 응답은 이 처리를 기다리지 않는다.
 *
 * <p>{@code @Async}를 쓰지 않고 실행기에 직접 낸다. {@code @Async}로 두면 실행기가 거절했을 때 예외가 Spring 안에서 로그만 남기고
 * 삼켜져(spring-tx 6.2.19에서 확인) 그 호출이 영원히 PENDING으로 남는다. 거절은 여기서 직접 잡아 그 호출만 FAILED로 끝낸다(작성자가 다시 물을 수
 * 있다). 거절 정책이 CallerRuns이면 요청 스레드가 LLM·외부 호출을 직접 하게 되므로 실행기는 AbortPolicy로 둔다.
 */
@Slf4j
@Component
public class KkiruCallListener {
    private final KkiruReplyService replyService;
    private final KkiruReplyWriter replyWriter;
    private final KkiruInFlight inFlight;
    private final Executor executor;

    public KkiruCallListener(
            KkiruReplyService replyService,
            KkiruReplyWriter replyWriter,
            KkiruInFlight inFlight,
            @Qualifier("communityKkiruTaskExecutor") Executor executor) {
        this.replyService = replyService;
        this.replyWriter = replyWriter;
        this.inFlight = inFlight;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKkiruCallRequested(KkiruCallRequestedEvent event) {
        KkiruInFlight.CallKey key = new KkiruInFlight.CallKey(event.postId(), event.commentId());
        inFlight.start(key);
        try {
            executor.execute(() -> run(event, key));
        } catch (RejectedExecutionException e) {
            inFlight.end(key);
            log.warn(
                    "[KKIRU_QUEUE_FULL] postId={} commentId={}", event.postId(), event.commentId());
            failQuietly(event);
        }
    }

    private void run(KkiruCallRequestedEvent event, KkiruInFlight.CallKey key) {
        try {
            replyService.reply(event.postId(), event.commentId());
        } finally {
            inFlight.end(key);
        }
    }

    /** 커밋 뒤 콜백에서는 이미 끝난 트랜잭션에 참여할 수 없어 새 트랜잭션으로 저장한다. 실패해도 요청 스레드로 번지지 않게 한다(정리 작업이 나중에 치운다). */
    private void failQuietly(KkiruCallRequestedEvent event) {
        try {
            replyWriter.markFailedInNewTransaction(event.postId(), event.commentId());
        } catch (RuntimeException e) {
            log.error(
                    "[KKIRU_QUEUE_FULL_MARK_ERROR] postId={} commentId={}",
                    event.postId(),
                    event.commentId(),
                    e);
        }
    }
}
