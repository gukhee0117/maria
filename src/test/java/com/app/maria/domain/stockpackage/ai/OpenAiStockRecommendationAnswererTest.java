package com.app.maria.domain.stockpackage.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** ChatClient를 가짜로 바꿔서 호출 흐름만 확인한다. 실제 OpenAI 응답은 이 테스트로 확인할 수 없다. */
class OpenAiStockRecommendationAnswererTest {
    private static final String PROMPT = "사실: <<FACTS>>";

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

    private void content(String text) {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(text);
    }

    @Test
    @DisplayName("답이 오면 앞뒤 공백을 지워서 돌려준다")
    void success() {
        content("  답이에요.  ");

        Optional<String> r =
                new OpenAiStockRecommendationAnswerer(chatClient, PROMPT, 2_000).answer("질문", "사실");

        assertThat(r).contains("답이에요.");
    }

    @Test
    @DisplayName("600자를 넘으면 마지막으로 끝난 문장까지만 남긴다")
    void cutsAtSentenceBoundary() {
        String sentence = "가나다라마바사아자차카타파하 ".repeat(3).trim() + ".";
        StringBuilder text = new StringBuilder();
        while (text.length() < 700) {
            text.append(sentence).append(' ');
        }

        String cut = OpenAiStockRecommendationAnswerer.cut(text.toString().trim());

        assertThat(cut.length()).isLessThanOrEqualTo(600);
        assertThat(cut).endsWith(".");
        assertThat(text.toString()).startsWith(cut);
    }

    @Test
    @DisplayName("문장 끝이 없으면 600자에서 자르고, 짧은 글은 그대로 둔다")
    void cutWithoutBoundaryAndShortText() {
        assertThat(OpenAiStockRecommendationAnswerer.cut("가".repeat(700))).hasSize(600);
        assertThat(OpenAiStockRecommendationAnswerer.cut("짧은 답.")).isEqualTo("짧은 답.");
        // 숫자 사이의 점은 문장 끝이 아니다
        String decimal = "가".repeat(595) + " 3.5나다라마바";
        assertThat(OpenAiStockRecommendationAnswerer.cut(decimal)).hasSize(600);
    }

    @Test
    @DisplayName("동시 호출이 상한에 차면 기다리지 않고 바로 빈 값을 준다")
    void rejectsImmediatelyWhenBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(
                        inv -> {
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return "느린 답";
                        });
        OpenAiStockRecommendationAnswerer answerer =
                new OpenAiStockRecommendationAnswerer(chatClient, PROMPT, 5_000, 1);

        Thread first = new Thread(() -> answerer.answer("질문1", "사실"));
        first.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        Optional<String> second = answerer.answer("질문2", "사실");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertThat(second).isEmpty();
        assertThat(elapsedMillis).isLessThan(1_000);
        release.countDown();
        first.join(3_000);
        answerer.shutdown();
    }
}
