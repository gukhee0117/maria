package com.app.maria.domain.stockpackage.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.stockpackage.ai.ConditionDraft.Intent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/** ChatClient를 가짜로 바꿔서 호출 흐름만 확인한다. 실제 OpenAI 응답은 이 테스트로 확인할 수 없다. */
class OpenAiStockConditionParserTest {
    private static final String PROMPT = "테마 목록: <<THEMES>>";
    private static final ConditionDraft DRAFT =
            new ConditionDraft(
                    Intent.FILTER, null, List.of("배당"), null, null, null, null, null, null);

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

    private final AiCallExecutor executor = new AiCallExecutor(10);

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private OpenAiStockConditionParser parser(long timeoutMillis) {
        return new OpenAiStockConditionParser(chatClient, PROMPT, timeoutMillis, executor);
    }

    @Test
    @DisplayName("고객의 말은 사용자 메시지로, 테마 목록은 시스템 프롬프트에 채워 보낸다")
    void success() {
        when(chatClient
                        .prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ConditionDraft.class))
                .thenReturn(DRAFT);

        Optional<ConditionDraft> result =
                parser(2_000).parse("배당 ETF", List.of("2차전지", "반도체", "배당"));

        assertThat(result).contains(DRAFT);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt(), atLeastOnce()).system(system.capture());
        assertThat(system.getAllValues()).contains("테마 목록: 2차전지, 반도체, 배당");
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt().system(anyString()), atLeastOnce()).user(user.capture());
        assertThat(user.getAllValues()).contains("배당 ETF");
    }

    @Test
    @DisplayName("AI 호출이 실패하면 예외 대신 빈 값을 돌려준다")
    void failure() {
        when(chatClient
                        .prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ConditionDraft.class))
                .thenThrow(new IllegalStateException("upstream"));

        assertThat(parser(2_000).parse("배당", List.of("배당"))).isEmpty();
    }

    @Test
    @DisplayName("응답을 읽을 수 없으면 빈 값을 돌려준다")
    void nullBody() {
        when(chatClient
                        .prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ConditionDraft.class))
                .thenReturn(null);

        assertThat(parser(2_000).parse("배당", List.of("배당"))).isEmpty();
    }

    @Test
    @DisplayName("시간이 초과되면 기다리지 않고 빈 값을 돌려준다")
    void timeout() {
        when(chatClient
                        .prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ConditionDraft.class))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(3_000);
                            return DRAFT;
                        });

        long start = System.nanoTime();
        Optional<ConditionDraft> result = parser(200).parse("배당", List.of("배당"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEmpty();
        assertThat(elapsedMillis).isLessThan(2_000);
    }
}
