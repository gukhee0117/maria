package com.app.maria.domain.stockpackage.ai;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * OpenAI로 추천 종목 질문에 답한다. 주어진 사실만 쓰게 하고, 어떤 실패도 예외가 아니라 빈 값으로 돌려준다. 질문과 응답은 로그에 남기지 않는다.
 *
 * <p>동시에 부르는 AI 호출은 MAX_THREADS개까지다. 모두 쓰는 중이면 기다리지 않고 바로 빈 값을 돌려준다. 호출한 쪽은 규칙 답변에 이미 걸리지 않은 질문이므로 AI 답이 없을 때의 기본 안내 문구를 쓰게 된다(혼잡과 정보 없음을 구분하는 문구는 후속 과제).
 */
@Component
@Slf4j
public class OpenAiStockRecommendationAnswerer implements StockRecommendationAnswerer {
    static final long DEFAULT_TIMEOUT_MILLIS = 8_000;
    private static final String FACTS_PLACEHOLDER = "<<FACTS>>";
    static final int MAX_THREADS = 10;
    private static final int MAX_ANSWER_LENGTH = 600;

    private final ChatClient chatClient;
    private final String promptTemplate;
    private final long timeoutMillis;
    private final ExecutorService executor;

    @Autowired
    public OpenAiStockRecommendationAnswerer(
            @Qualifier("stockPackageChatClient") ChatClient chatClient,
            @Qualifier("stockRecommendationPrompt") String promptTemplate) {
        this(chatClient, promptTemplate, DEFAULT_TIMEOUT_MILLIS, MAX_THREADS);
    }

    OpenAiStockRecommendationAnswerer(
            ChatClient chatClient, String promptTemplate, long timeoutMillis) {
        this(chatClient, promptTemplate, timeoutMillis, MAX_THREADS);
    }

    OpenAiStockRecommendationAnswerer(
            ChatClient chatClient, String promptTemplate, long timeoutMillis, int maxThreads) {
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
        this.timeoutMillis = timeoutMillis;
        // 대기열 없이 상한까지만 스레드를 만들고, 꽉 차면 submit이 바로 RejectedExecutionException을 던진다
        this.executor =
                new ThreadPoolExecutor(
                        0,
                        maxThreads,
                        60L,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "stock-recommendation-answerer");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public Optional<String> answer(String question, String facts) {
        String system = promptTemplate.replace(FACTS_PLACEHOLDER, facts);
        Future<String> future;
        try {
            future =
                    executor.submit(
                            () ->
                                    chatClient
                                            .prompt()
                                            .system(system)
                                            .user(question)
                                            .call()
                                            .content());
        } catch (RejectedExecutionException e) {
            log.warn("STOCK_RECOMMENDATION_ANSWER_BUSY");
            return Optional.empty();
        }
        try {
            String text = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(cut(text.trim()));
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("STOCK_RECOMMENDATION_ANSWER_TIMEOUT");
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        } catch (ExecutionException | RuntimeException e) {
            Throwable t = e.getCause() == null ? e : e.getCause();
            log.warn("STOCK_RECOMMENDATION_ANSWER_FAILED cause={}", t.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 600자를 넘으면 그 안에서 마지막으로 끝난 문장까지만 남긴다. 문장 끝이 없으면 600자에서 자른다. */
    static String cut(String text) {
        if (text.length() <= MAX_ANSWER_LENGTH) {
            return text;
        }
        String head = text.substring(0, MAX_ANSWER_LENGTH);
        int end = -1;
        for (int i = head.length() - 1; i > 0; i--) {
            char c = head.charAt(i);
            // 3.5처럼 숫자 사이의 점은 문장 끝이 아니므로, 뒤가 공백이거나 글이 끝나는 경우만 문장 끝으로 본다
            char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
            boolean ender = c == '.' || c == '!' || c == '?' || c == '\n';
            if (ender && Character.isWhitespace(next)) {
                end = i;
                break;
            }
        }
        return end > 0 ? head.substring(0, end + 1).trim() : head;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
