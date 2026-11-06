package com.app.maria.domain.news.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.mapper.DomesticStockBalanceMapper;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.news.cache.NewsSummaryCache;
import com.app.maria.domain.news.client.NaverNewsSearchClient;
import com.app.maria.domain.news.provider.*;
import com.app.maria.domain.news.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

class NewsConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NewsProperties.class)
    @Import({
        NewsAiConfig.class,
        MockNewsProvider.class,
        PreparedNewsResponses.class,
        NewsCustomerContextService.class,
        NewsSelectionService.class,
        NewsAiService.class,
        NewsBriefingService.class,
        NewsSummaryCache.class,
        NaverNewsSearchClient.class,
        com.app.maria.domain.news.client.FreeNewsClient.class,
        FreeNewsProvider.class
    })
    static class Config {}

    @Test
    void newBeansWireAlongsideExistingChatClientWithoutDatabaseOrFinanceChanges() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withBean(
                        ObjectMapper.class,
                        () -> new ObjectMapper().registerModule(new JavaTimeModule()))
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withBean("kkiruChatClient", ChatClient.class, () -> mock(ChatClient.class))
                .withBean(AccountMapper.class, () -> mock(AccountMapper.class))
                .withBean(
                        DomesticStockBalanceMapper.class,
                        () -> mock(DomesticStockBalanceMapper.class))
                .withBean(
                        InvestorAssessmentService.class,
                        () -> mock(InvestorAssessmentService.class))
                .withPropertyValues("news.naver-client-id=id", "news.naver-client-secret=secret")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(NewsBriefingService.class)
                                    .hasSingleBean(NaverNewsSearchClient.class)
                                    .hasSingleBean(NewsProvider.class);
                            assertThat(context.getBean("newsChatClient"))
                                    .isNotSameAs(context.getBean("kkiruChatClient"));
                            assertThat(context.getBean(NewsProperties.class).getNaverClientId())
                                    .isEqualTo("id");
                        });
    }
}
