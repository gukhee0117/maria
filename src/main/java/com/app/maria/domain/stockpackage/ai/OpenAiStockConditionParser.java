package com.app.maria.domain.stockpackage.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * OpenAI로 고객의 말을 조건 초안으로 바꾼다. 응답은 {@link ConditionDraft} 스키마로만 받고, 어떤 실패도 예외가 아니라 빈 값으로 돌려준다. 고객의
 * 말과 AI 응답 내용은 로그에 남기지 않는다. 동시 호출 수는 추천 질문 답변과 함께 {@link AiCallExecutor}가 묶고, 모두 쓰는 중이면 빈 값을 돌려준다.
 */
@Component
@Slf4j
public class OpenAiStockConditionParser implements StockConditionParser {
    static final long DEFAULT_TIMEOUT_MILLIS = 8_000;
    private static final String THEMES_PLACEHOLDER = "<<THEMES>>";

    private final ChatClient chatClient;
    private final String promptTemplate;
    private final long timeoutMillis;
    private final AiCallExecutor executor;

    @Autowired
    public OpenAiStockConditionParser(
            @Qualifier("stockPackageChatClient") ChatClient chatClient,
            @Qualifier("stockConditionPrompt") String promptTemplate,
            AiCallExecutor executor) {
        this(chatClient, promptTemplate, DEFAULT_TIMEOUT_MILLIS, executor);
    }

    OpenAiStockConditionParser(
            ChatClient chatClient,
            String promptTemplate,
            long timeoutMillis,
            AiCallExecutor executor) {
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
        this.timeoutMillis = timeoutMillis;
        this.executor = executor;
    }

    @Override
    public Optional<ConditionDraft> parse(String text, List<String> themeNames) {
        String system = promptTemplate.replace(THEMES_PLACEHOLDER, String.join(", ", themeNames));
        Future<ConditionDraft> future;
        try {
            future =
                    executor.submit(
                            () ->
                                    chatClient
                                            .prompt()
                                            .system(system)
                                            .user(text)
                                            .call()
                                            .entity(ConditionDraft.class));
        } catch (AiBusyException e) {
            log.warn("STOCK_CONDITION_PARSE_BUSY");
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("STOCK_CONDITION_PARSE_TIMEOUT");
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("STOCK_CONDITION_PARSE_FAILED cause={}", rootName(e));
            return Optional.empty();
        }
    }

    private static String rootName(Throwable e) {
        Throwable t = e.getCause() == null ? e : e.getCause();
        return t.getClass().getSimpleName();
    }
}
