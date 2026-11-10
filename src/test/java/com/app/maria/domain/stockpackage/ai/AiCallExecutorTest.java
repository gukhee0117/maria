package com.app.maria.domain.stockpackage.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class AiCallExecutorTest {

    @Test
    @DisplayName("상한까지 모두 쓰는 중이면 기다리지 않고 바로 AiBusyException을 던진다")
    void rejectsWhenFull() throws Exception {
        AiCallExecutor executor = new AiCallExecutor(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(
                () -> {
                    release.await(5, TimeUnit.SECONDS);
                    return "첫 호출";
                });

        assertThatThrownBy(() -> executor.submit(() -> "둘째 호출"))
                .isInstanceOf(AiBusyException.class);

        release.countDown();
        executor.shutdown();
    }

    @Test
    @DisplayName("추천 질문 답변과 조건 변환이 같은 상한을 나눠 쓴다")
    void answererAndParserShareOneLimit() throws Exception {
        ChatClient answerClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient parseClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(answerClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(
                        inv -> {
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return "느린 답";
                        });
        AiCallExecutor shared = new AiCallExecutor(1);
        OpenAiStockRecommendationAnswerer answerer =
                new OpenAiStockRecommendationAnswerer(answerClient, "사실: <<FACTS>>", 5_000, shared);
        OpenAiStockConditionParser parser =
                new OpenAiStockConditionParser(parseClient, "테마: <<THEMES>>", 5_000, shared);

        Thread first = new Thread(() -> answerer.answer("질문", "사실"));
        first.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // 답변 호출이 유일한 자리를 쓰는 동안 조건 변환은 기다리지 않고 빈 값이 된다
        Optional<ConditionDraft> parsed = parser.parse("배당주 보여줘", List.of("배당"));
        assertThat(parsed).isEmpty();

        release.countDown();
        first.join(3_000);
        shared.shutdown();
    }
}
