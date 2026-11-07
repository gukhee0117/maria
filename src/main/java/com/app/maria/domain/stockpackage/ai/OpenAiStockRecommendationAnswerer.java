package com.app.maria.domain.stockpackage.ai;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** OpenAI로 추천 종목 질문에 답한다. 주어진 사실만 쓰게 하고, 어떤 실패도 예외가 아니라 빈 값으로 돌려준다. 질문과 응답은 로그에 남기지 않는다. */
@Component
@Slf4j
public class OpenAiStockRecommendationAnswerer implements StockRecommendationAnswerer {
    static final long DEFAULT_TIMEOUT_MILLIS = 8_000;
    private static final String FACTS_PLACEHOLDER = "<<FACTS>>";
    private static final int MAX_ANSWER_LENGTH = 600;

    private final ChatClient chatClient;
    private final String promptTemplate;
    private final long timeoutMillis;
    private final ExecutorService executor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "stock-recommendation-answerer");
                        t.setDaemon(true);
                        return t;
                    });

    @Autowired
    public OpenAiStockRecommendationAnswerer(
            @Qualifier("stockPackageChatClient") ChatClient chatClient,
            @Qualifier("stockRecommendationPrompt") String promptTemplate) {
        this(chatClient, promptTemplate, DEFAULT_TIMEOUT_MILLIS);
    }

    OpenAiStockRecommendationAnswerer(
            ChatClient chatClient, String promptTemplate, long timeoutMillis) {
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Optional<String> answer(String question, String facts) {
        String system = promptTemplate.replace(FACTS_PLACEHOLDER, facts);
        Future<String> future =
                executor.submit(
                        () -> chatClient.prompt().system(system).user(question).call().content());
        try {
            String text = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String trimmed = text.trim();
            return Optional.of(
                    trimmed.length() > MAX_ANSWER_LENGTH
                            ? trimmed.substring(0, MAX_ANSWER_LENGTH)
                            : trimmed);
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

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
