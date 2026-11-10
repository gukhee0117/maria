package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.cache.NewsSummaryCache;
import com.app.maria.domain.news.config.*;
import com.app.maria.domain.news.dto.*;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class NewsSharedSummaryTest {
    @Test
    void differentCustomersShareSummaryTextWithoutAutomaticallyReceivingStockCards() {
        var contexts = mock(NewsCustomerContextService.class);
        var provider = mock(NewsProvider.class, CALLS_REAL_METHODS);
        var ai = mock(NewsAiService.class);
        var properties = new NewsProperties();
        properties.setLiveSummaries(true);
        var original = NewsSelectionServiceTest.article("a", 5, 5);
        var article =
                new NewsArticle(
                        original.id(),
                        original.version(),
                        original.title(),
                        original.body(),
                        original.source(),
                        original.url(),
                        original.publishedAt(),
                        List.of("005930", "000660"),
                        original.facts(),
                        original.risks(),
                        5,
                        5,
                        5,
                        false,
                        List.of(
                                new NewsStockReference("005930", "삼성전자", "전기·전자"),
                                new NewsStockReference("000660", "SK하이닉스", "전기·전자")));
        when(provider.id()).thenReturn("demo");
        when(provider.articles(any(Context.class))).thenReturn(List.of(article));
        when(contexts.load(1L))
                .thenReturn(
                        new Context(
                                1L,
                                Set.of("005930"),
                                InvestorType.STABLE,
                                PersonalizationStatus.ACTIVE,
                                Map.of("005930", "삼성전자")));
        when(contexts.load(2L))
                .thenReturn(
                        new Context(
                                2L,
                                Set.of("000660"),
                                InvestorType.STABLE,
                                PersonalizationStatus.ACTIVE,
                                Map.of("000660", "SK하이닉스")));
        when(ai.summarize(any(), any())).thenReturn("공통 요약");
        var service =
                new NewsBriefingService(
                        contexts,
                        new NewsSelectionService(),
                        provider,
                        ai,
                        new PreparedNewsResponses(),
                        new NewsSummaryCache(),
                        properties,
                        () -> java.time.LocalDateTime.of(2026, 11, 6, 12, 0));
        var first = service.summary(1L, "a", "v1");
        var second = service.summary(2L, "a", "v1");
        assertThat(second.origin()).isEqualTo(Origin.CACHED_LLM);
        assertThat(second.generatedAt()).isEqualTo(first.generatedAt());
        assertThat(first.relatedStocks()).isEmpty();
        assertThat(second.relatedStocks()).isEmpty();
        verify(ai, times(1)).summarize(any(), any());
    }

    @Test
    void identityTracksExactPublicInputProfilePromptAndModel() {
        var a = NewsSelectionServiceTest.article("a", 5, 5);
        var key = NewsCacheIdentity.summary("p", a, InvestorType.STABLE, "prompt1", "same-model");
        assertThat(
                        NewsCacheIdentity.summary(
                                "p", a, InvestorType.AGGRESSIVE, "prompt1", "same-model"))
                .isNotEqualTo(key);
        assertThat(NewsCacheIdentity.summary("p", a, InvestorType.STABLE, "prompt2", "same-model"))
                .isNotEqualTo(key);
        assertThat(
                        NewsCacheIdentity.summary(
                                "p", a, InvestorType.STABLE, "prompt1", "different-model"))
                .isNotEqualTo(key);
        var changed =
                new NewsArticle(
                        a.id(),
                        a.version(),
                        a.title(),
                        a.body() + "본문 변경",
                        a.source(),
                        a.url(),
                        a.publishedAt(),
                        a.tickers(),
                        a.facts(),
                        a.risks(),
                        a.importance(),
                        a.stability(),
                        a.growth());
        assertThat(
                        NewsCacheIdentity.summary(
                                "p", changed, InvestorType.STABLE, "prompt1", "same-model"))
                .isNotEqualTo(key);
    }

    @Test
    void summaryPromptNeverIncludesCustomerContextAndInvalidNumbersOrAdviceAreRejected()
            throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        invocation ->
                                model.stream((Prompt) invocation.getArgument(0)).blockFirst());
        var ai =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, new NewsProperties()),
                        new ObjectMapper());
        var context =
                new Context(
                        123987L,
                        Set.of("SECRET_TICKER"),
                        InvestorType.STABLE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("SECRET_TICKER", "PRIVATE_CUSTOMER_CONTEXT"));
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        response(
                                "{\"explanation\":\"\",\"points\":[\"사실\",\"위험\",\"사실 확인\"],\"quote\":null}"));
        ai.summarize(NewsSelectionServiceTest.article("a", 5, 5), context);
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertThat(prompt.getValue().toString())
                .doesNotContain(
                        "123987",
                        "SECRET_TICKER",
                        "PRIVATE_CUSTOMER_CONTEXT",
                        "knownStocks",
                        "history");
        when(model.stream(any(Prompt.class)))
                .thenReturn(response("{\"points\":[\"999원\",\"사실\",\"위험\"]}"));
        assertThatThrownBy(() -> ai.summarize(NewsSelectionServiceTest.article("a", 5, 5), context))
                .hasMessageContaining("numbers absent");
        when(model.stream(any(Prompt.class)))
                .thenReturn(response("{\"points\":[\"매수 추천합니다\",\"사실\",\"위험\"]}"));
        assertThatThrownBy(() -> ai.summarize(NewsSelectionServiceTest.article("a", 5, 5), context))
                .hasMessageContaining("investment advice");
    }

    private Flux<ChatResponse> response(String text) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
