package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.cache.NewsSummaryCache;
import com.app.maria.domain.news.client.FreeNewsClient;
import com.app.maria.domain.news.config.*;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

/** Opt-in: actual news search -> mapped body -> Spring AI -> summary/cache/question. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "NEWS_RUN_LIVE_TEST", matches = "true")
class NewsFreeIntegrationTest {
    @Test
    void actualNewsCanBeSummarizedAndAskedAboutWithoutDemoFallback() throws Exception {
        var properties = new NewsProperties();
        properties.setProvider("free");
        properties.setFreeNewsApiKey(System.getenv("FREENEWS_API_KEY"));
        var provider =
                new FreeNewsProvider(
                        new FreeNewsClient(properties, null),
                        properties,
                        () ->
                                java.time.LocalDateTime.ofInstant(
                                        java.time.Instant.now(), java.time.ZoneId.of("Asia/Seoul")),
                        java.time.Clock.systemUTC());
        var context =
                new Context(
                        1L,
                        Set.of("005930", "000660"),
                        InvestorType.STABLE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("005930", "삼성전자", "000660", "SK하이닉스"));
        var contexts = mock(NewsCustomerContextService.class);
        when(contexts.loadForSelection(anyLong()))
                .thenAnswer(invocation -> contexts.load(invocation.getArgument(0)));
        when(contexts.load(1L)).thenReturn(context);
        var model =
                OpenAiChatModel.builder()
                        .openAiApi(
                                OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build())
                        .build();
        var ai =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, properties),
                        new ObjectMapper().findAndRegisterModules());
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
        var briefing = service.briefing(1L);
        assertThat(briefing.articles()).isNotEmpty();
        var article = briefing.articles().get(0).article();
        assertThat(article.demo()).isFalse();
        assertThat(article.body()).hasSizeGreaterThanOrEqualTo(100);
        assertThat(article.url()).startsWith("http");
        var summary = service.summary(1L, article.id(), article.version());
        assertThat(summary.origin()).isEqualTo(Origin.LIVE_LLM);
        assertThat(summary.explanation()).isNotBlank();
        assertThat(summary.points()).hasSize(3);
        assertThat(summary.relatedStocks()).isNotEmpty();
        assertThat(service.summary(1L, article.id(), article.version()).origin())
                .isEqualTo(Origin.CACHED_LLM);
        var answer =
                service.question(
                        1L,
                        article.id(),
                        new QuestionRequest(article.version(), "기사에 나온 숫자만 정리해줘", List.of()));
        assertThat(answer.origin()).isEqualTo(Origin.LIVE_LLM);
        assertThat(answer.explanation()).isNotBlank();
        assertThat(
                        new com.app.maria.domain.news.dto.NewsAnswerContent(
                                        answer.explanation(), answer.points(), answer.quote())
                                .numbersComeFrom(article))
                .isTrue();
        if (answer.quote() != null) assertThat(article.body()).contains(answer.quote());
        System.out.println(
                "Actual news pipeline verified: selected="
                        + briefing.articles().size()
                        + ", bodyChars="
                        + article.body().length()
                        + ", summary="
                        + summary.origin()
                        + ", answer="
                        + answer.origin());
    }
}
