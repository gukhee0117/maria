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
    void themeAndStockMarketContextAreBothRequiredForFreshAndCachedArticles() {
        var source = mock(FreeNewsProvider.class);
        var themes = mock(NewsThemeService.class);
        var context =
                new Context(
                        1L,
                        Set.of("005930"),
                        InvestorType.STABLE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("005930", "삼성전자"));
        when(themes.themes(context.tickers())).thenReturn(Map.of("005930", Set.of("반도체")));
        var rows =
                List.of(
                        news("market", "반도체 업종 주가 상승", "반도체 수요 회복에 증권사는 목표주가를 높였다."),
                        news("body-market", "반도체 수요 회복", "반도체 기업의 주가와 코스피 지수 상승이 이어졌다."),
                        news("technology", "반도체 기술 체험 행사", "반도체 원리를 알아보는 학생 체험 행사가 열렸다."),
                        news("policy", "반도체 산업 육성 정책", "정부가 반도체 생산과 공급 확대를 위한 투자를 발표했다."),
                        news("unrelated", "바이오 주가 상승", "바이오 업종에 대한 증권사 투자의견이다."),
                        news("footer", "반도체 체험 행사", "반도체 원리 교육이 진행됐다. ※ 주식 투자에 주의하세요."),
                        news("company", "반도체 주식회사 행사", "반도체 주식회사가 봉사 행사를 열었다."));
        when(source.articles(any(Context.class))).thenReturn(rows);
        when(source.cachedArticles(any(Context.class))).thenReturn(Optional.of(rows));
        var provider = new ThemeNewsProvider(source, themes);
        assertThat(provider.articles(context))
                .extracting(NewsArticle::id)
                .containsExactly("market", "body-market");
        assertThat(provider.cachedArticles(context))
                .get()
                .asList()
                .extracting("id")
                .containsExactly("market", "body-market");
        when(source.articles(any(Context.class))).thenReturn(List.of(rows.get(2)));
        assertThat(provider.articles(context)).isEmpty();
    }

    @Test
    void excludesPromotionalTitlesButKeepsMarketStoriesAndFinancialRightsExercise() {
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
        var rows =
                List.of(
                        news("contest", "하나증권 반도체 창업공모전 개최", "반도체 주식 투자 아이디어 공모전 참가자를 모집한다."),
                        news("event", "반도체 주식 거래 이벤트", "반도체 주식 거래 고객에게 경품을 지급한다."),
                        news("seminar", "반도체 투자 세미나 개최", "증권사가 반도체 투자 설명회를 개최한다."),
                        news("festival", "반도체 체험 행사 참가자 모집", "증권사가 반도체 행사를 후원한다."),
                        news(
                                "earnings",
                                "반도체 실적 개선에 주가 상승",
                                "반도체 기업의 실적이 개선됐다. 앞서 열린 행사에서도 실적 전망이 논의됐다."),
                        news(
                                "rights",
                                "반도체 기업 신주인수권 행사로 주식 수 증가",
                                "반도체 기업의 신주인수권 행사로 주식 수가 증가했다."));
        when(source.articles(any(Context.class))).thenReturn(rows);
        when(source.cachedArticles(any(Context.class))).thenReturn(Optional.of(rows));
        when(source.snapshot(any()))
                .thenReturn(new NewsProvider.Snapshot("day", () -> rows, () -> Optional.of(rows)));
        var provider = new ThemeNewsProvider(source, themes);
        assertThat(provider.articles(c))
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights");
        assertThat(provider.cachedArticles(c).orElseThrow())
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights");
        var captured = provider.snapshot(c);
        assertThat(captured.articles().get())
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights");
        assertThat(captured.cachedArticles().get().orElseThrow())
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights");
    }

    private NewsArticle news(String id, String title, String body) {
        return new NewsArticle(
                id,
                "v1",
                title,
                body,
                "source",
                "https://example.com/" + id,
                LocalDateTime.of(2026, 11, 9, 9, 0),
                List.of("theme:반도체"),
                List.of(),
                List.of(),
                5,
                5,
                5,
                false);
    }

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
                        "SK하이닉스 반도체 주가 전망",
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
