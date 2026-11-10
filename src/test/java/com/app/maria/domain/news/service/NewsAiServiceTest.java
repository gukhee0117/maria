package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.chat.dto.request.MessageDTO;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.config.NewsAiConfig;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class NewsAiServiceTest {
    @Test
    void realChatClientBuildsIndependentPromptWithOnlySelectedArticleAndNewsHistory()
            throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        invocation ->
                                model.stream((Prompt) invocation.getArgument(0)).blockFirst());
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage(
                                                                "{\"explanation\":\"기사 설명\",\"points\":[],\"quote\":\"사실\"}"))))));
        var client = new NewsAiConfig().newsChatClient(model, new NewsProperties());
        var service =
                new NewsAiService(client, new ObjectMapper().registerModule(new JavaTimeModule()));
        var request =
                new QuestionRequest(
                        "v1",
                        "위험은?",
                        List.of(
                                new MessageDTO("user", "이 기사 질문"),
                                new MessageDTO("assistant", "이 기사 답변")));
        assertThat(
                        service.question(
                                NewsSelectionServiceTest.article("a", 5, 5),
                                NewsSelectionServiceTest.context(InvestorType.STABLE),
                                request))
                .contains("기사 설명");
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(captor.capture());
        var prompt = captor.getValue();
        assertThat(prompt.getSystemMessage().getText()).contains("뉴스 전용", "중요한 사실과 위험");
        assertThat(prompt.getUserMessage().getText())
                .contains("이 기사 질문", "위험은?", "사실 위험", "STABLE")
                .doesNotContain("customerId", "accountId", "naverapihub", "kkiru/system-prompt");
        assertThat(prompt.getOptions().getModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void markdownFenceAroundValidJsonDoesNotCauseAnAnswerFailure() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        invocation ->
                                model.stream((Prompt) invocation.getArgument(0)).blockFirst());
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage(
                                                                "```json\n{\"explanation\":\"관련 종목 설명\",\"points\":[],\"quote\":\"사실\"}\n```"))))));
        var service =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, new NewsProperties()),
                        new ObjectMapper().findAndRegisterModules());
        var result =
                service.question(
                        NewsSelectionServiceTest.article("a", 5, 5),
                        NewsSelectionServiceTest.context(InvestorType.STABLE),
                        new QuestionRequest("v1", "관련 종목이 뭐야?", List.of()));
        assertThat(result).startsWith("{").contains("관련 종목 설명").doesNotContain("```");
    }

    @Test
    void generalArticleQuestionsAlsoRejectInventedNumbers() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        invocation ->
                                model.stream((Prompt) invocation.getArgument(0)).blockFirst());
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage(
                                                                "{\"explanation\":\"\",\"points\":[\"매출이 999억원이에요.\"],\"quote\":null}"))))));
        var service =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, new NewsProperties()),
                        new ObjectMapper().findAndRegisterModules());
        assertThatThrownBy(
                        () ->
                                service.question(
                                        NewsSelectionServiceTest.article("a", 5, 5),
                                        NewsSelectionServiceTest.context(InvestorType.STABLE),
                                        new QuestionRequest("v1", "이 기사 내용이 뭐야?", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("numbers absent");
    }

    @Test
    void numericQuestionsRejectValuesNotPresentInTheArticle() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        invocation ->
                                model.stream((Prompt) invocation.getArgument(0)).blockFirst());
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage(
                                                                "{\"explanation\":\"\",\"points\":[\"매출이 999억원이에요.\"],\"quote\":null}"))))));
        var service =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, new NewsProperties()),
                        new ObjectMapper().findAndRegisterModules());
        assertThatThrownBy(
                        () ->
                                service.question(
                                        NewsSelectionServiceTest.article("a", 5, 5),
                                        NewsSelectionServiceTest.context(InvestorType.STABLE),
                                        new QuestionRequest("v1", "기사에 나온 숫자만 정리해줘", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("numbers absent");
    }

    @Test
    void emptyAndFailedModelResponsesAreRejectedForExplicitFallback() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        invocation ->
                                model.stream((Prompt) invocation.getArgument(0)).blockFirst());
        var service =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, new NewsProperties()),
                        new ObjectMapper().registerModule(new JavaTimeModule()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.empty());
        assertThatThrownBy(
                        () ->
                                service.summarize(
                                        NewsSelectionServiceTest.article("a", 5, 5),
                                        NewsSelectionServiceTest.context(InvestorType.ACTIVE)))
                .isInstanceOf(RuntimeException.class);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("offline")));
        assertThatThrownBy(
                        () ->
                                service.summarize(
                                        NewsSelectionServiceTest.article("a", 5, 5),
                                        NewsSelectionServiceTest.context(InvestorType.ACTIVE)))
                .isInstanceOf(RuntimeException.class);
    }
}
