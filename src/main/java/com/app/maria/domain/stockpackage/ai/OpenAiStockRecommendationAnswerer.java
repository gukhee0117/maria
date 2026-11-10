package com.app.maria.domain.stockpackage.ai;

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
 * OpenAI로 추천 종목 질문에 답한다. 주어진 사실만 쓰게 하고, 호출이 실패하거나 시간이 지나면 예외가 아니라 빈 값으로 돌려준다. 질문과 응답은 로그에 남기지 않는다.
 *
 * <p>동시에 부르는 AI 호출은 {@link AiCallExecutor}가 묶는다. 모두 쓰는 중이면 기다리지 않고 {@link AiBusyException}을 던진다.
 * 일시적인 혼잡은 "답할 정보가 없음"과 달라서, 호출한 쪽이 빈 값과 구분해 다시 시도하라고 안내할 수 있게 하려는 것이다.
 */
@Component
@Slf4j
public class OpenAiStockRecommendationAnswerer implements StockRecommendationAnswerer {
    static final long DEFAULT_TIMEOUT_MILLIS = 8_000;
    private static final String FACTS_PLACEHOLDER = "<<FACTS>>";
    private static final int MAX_ANSWER_LENGTH = 600;
    // 문장 끝 기호 뒤에 붙을 수 있는 닫는 따옴표·괄호
    private static final String CLOSERS = "\"'”’)]」』";

    private final ChatClient chatClient;
    private final String promptTemplate;
    private final long timeoutMillis;
    private final AiCallExecutor executor;

    @Autowired
    public OpenAiStockRecommendationAnswerer(
            @Qualifier("stockPackageChatClient") ChatClient chatClient,
            @Qualifier("stockRecommendationPrompt") String promptTemplate,
            AiCallExecutor executor) {
        this(chatClient, promptTemplate, DEFAULT_TIMEOUT_MILLIS, executor);
    }

    OpenAiStockRecommendationAnswerer(
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
        } catch (AiBusyException e) {
            log.warn("STOCK_RECOMMENDATION_ANSWER_BUSY");
            throw e;
        }
        try {
            String text = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(cut(text.trim()));
        } catch (TimeoutException e) {
            // cancel(true)는 스레드에 인터럽트를 건다. 이 서버의 HTTP 클라이언트(Reactor)는 인터럽트에 반응해 바로 호출을 끊고 스레드를 돌려준다
            // (응답이 오지 않는 서버로 확인: 취소 뒤 수 ms 안에 풀림). 클라이언트 구현을 바꾸면 이 가정을 다시 확인해야 한다
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

    /**
     * 600자를 넘으면 그 안에서 마지막으로 끝난 문장까지만 남긴다. 문장 끝은 먼저 마침표·느낌표·물음표(뒤에 닫는 따옴표·괄호가 올 수 있다)로 찾고, 없으면 마침표
     * 없이 끝나는 "~요"·"~다"로 찾는다. 둘 다 없으면 600자에서 자른다.
     */
    static String cut(String text) {
        if (text.length() <= MAX_ANSWER_LENGTH) {
            return text;
        }
        String head = text.substring(0, MAX_ANSWER_LENGTH);
        int end = lastSentenceEnd(text, head.length(), true);
        if (end < 0) {
            end = lastSentenceEnd(text, head.length(), false);
        }
        return end >= 0 ? head.substring(0, end + 1).trim() : head;
    }

    /** limit 앞에서 마지막으로 끝난 문장의 마지막 글자 위치를 찾는다. 없으면 -1이다. */
    private static int lastSentenceEnd(String text, int limit, boolean byPunctuation) {
        for (int i = limit - 1; i >= 0; i--) {
            char c = text.charAt(i);
            // 한계: "맞다 보니"처럼 절을 잇는 "다" 뒤 공백도 문장 끝으로 판정한다.
            // 600자 안에 마침표 등이 전혀 없을 때만 쓰는 차선책이라 허용한다.
            boolean ender =
                    byPunctuation
                            ? c == '.' || c == '!' || c == '?' || c == '\n'
                            : c == '요' || c == '다';
            if (!ender) {
                continue;
            }
            int last = i;
            if (byPunctuation) {
                while (last + 1 < limit && CLOSERS.indexOf(text.charAt(last + 1)) >= 0) {
                    last++;
                }
            }
            // 3.5처럼 숫자 사이의 점은 문장 끝이 아니므로, 뒤가 공백이거나 글이 끝나는 경우만 문장 끝으로 본다
            char next = last + 1 < text.length() ? text.charAt(last + 1) : ' ';
            if (Character.isWhitespace(next)) {
                return last;
            }
        }
        return -1;
    }
}
