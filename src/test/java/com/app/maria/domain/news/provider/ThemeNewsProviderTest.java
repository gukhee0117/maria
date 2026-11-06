package com.app.maria.domain.news.provider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.*;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.service.NewsThemeService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ThemeNewsProviderTest {
    @Test
    void searchesOnlyDatabaseThemesAndProjectsOwnedThemeLinksWithoutCompanyMentionRequirement() {
        var source = mock(FreeNewsProvider.class);
        var themes = mock(NewsThemeService.class);
        var c =
                new Context(
                        1L,
                        Set.of("005930"),
                        InvestorType.STABLE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("005930", "삼성전자"));
        when(themes.themes(c.tickers())).thenReturn(Map.of("005930", Set.of("반도체")));
        var article =
                new NewsArticle(
                        "a",
                        "v1",
                        "SK하이닉스 반도체 산업 투자",
                        "SK하이닉스 공급망 확대 내용",
                        "source",
                        "https://example.com",
                        LocalDateTime.now(),
                        List.of("theme:반도체"),
                        List.of("사실"),
                        List.of("위험"),
                        5,
                        5,
                        5,
                        false);
        when(themes.mentionedStocks(article))
                .thenReturn(List.of(new NewsStockReference("000660", "SK하이닉스", "전기·전자")));
        when(source.articles(any(Context.class))).thenReturn(List.of(article));
        when(source.cachedArticles(any())).thenReturn(Optional.of(List.of(article)));
        var provider = new ThemeNewsProvider(source, themes);
        assertThat(provider.articles(c))
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.tickers()).containsExactly("005930");
                            assertThat(a.relatedStocks())
                                    .containsExactly(
                                            new NewsStockReference("000660", "SK하이닉스", "전기·전자"));
                        });
        var query = org.mockito.ArgumentCaptor.forClass(Context.class);
        verify(source).articles(query.capture());
        assertThat(query.getValue().stockNames().values()).containsExactly("반도체");
        assertThat(query.getValue().stockNames().values()).doesNotContain("삼성전자");
        assertThat(provider.cachedArticles(c)).isPresent();
        verify(source, times(1)).articles(any(Context.class));
        when(themes.themes(c.tickers())).thenReturn(Map.of());
        assertThat(provider.articles(c)).isEmpty();
        verify(source, times(1)).articles(any(Context.class));
    }
}
