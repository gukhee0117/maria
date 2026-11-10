package com.app.maria.domain.stockpackage.ai;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주식 패키지의 AI 호출(추천 질문 답변, 말로 조건 찾기)이 함께 쓰는 스레드 풀이다. 두 호출기가 풀을 따로 만들면 서로의 상한이 보이지 않으므로 한 곳에서 동시 호출
 * 수를 묶는다. 대기열이 없어서, 모두 쓰는 중이면 기다리지 않고 바로 {@link AiBusyException}을 던진다.
 */
@Component
public class AiCallExecutor {
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor;

    /** 동시 호출 상한은 설정(app.stockpackage.ai-call-max-threads)으로 조정하고, 없으면 10이다. */
    @Autowired
    public AiCallExecutor(@Value("${app.stockpackage.ai-call-max-threads:10}") int maxThreads) {
        this.executor =
                new ThreadPoolExecutor(
                        0,
                        maxThreads,
                        60L,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            Thread t =
                                    new Thread(r, "stock-ai-call-" + threadSeq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /** 호출을 풀에 맡긴다. 상한까지 모두 쓰는 중이면 {@link AiBusyException}을 던진다. */
    public <T> Future<T> submit(Callable<T> call) {
        try {
            return executor.submit(call);
        } catch (RejectedExecutionException e) {
            throw new AiBusyException();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
