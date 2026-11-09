package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.config.NewsAiConfig;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.MockNewsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

/** Explicit opt-in: makes one real LLM request, never invoked by normal CI. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "NEWS_RUN_LIVE_TEST", matches = "true")
class NewsLiveIntegrationTest {
    @Test
    void followUpUsesActualSpringAiWithSelfAuthoredDomesticArticle() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var model =
                OpenAiChatModel.builder()
                        .openAiApi(
                                OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build())
                        .build();
        var ai =
                new NewsAiService(
                        new NewsAiConfig().newsChatClient(model, new NewsProperties()), mapper);
        var article = new MockNewsProvider(mapper).articles().get(0);
        var context =
                new Context(
                        1L, Set.of("005930"), InvestorType.STABLE, PersonalizationStatus.ACTIVE);
        var result =
                ai.question(
                        article,
                        context,
                        new QuestionRequest(article.version(), "이 기사의 위험을 설명해 주세요.", List.of()));
        assertThat(result).isNotBlank().hasSizeLessThanOrEqualTo(2000);
    }
}
