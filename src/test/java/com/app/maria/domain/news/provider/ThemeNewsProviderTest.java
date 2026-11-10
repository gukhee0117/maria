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
                        news("rights", "반도체 기업 신주인수권 행사로 주식 수 증가", "반도체 기업의 신주인수권 행사로 주식 수가 증가했다."),
                        news("agency", "반도체 광고대행사 실적 개선에 주가 상승", "반도체 광고대행사의 주가가 올랐다."),
                        news(
                                "conversion",
                                "반도체 기업 전환청구권 행사로 주식 수 증가",
                                "반도체 기업의 전환청구권 행사로 주식 수가 증가했다."),
                        news("conversion-spaced", "반도체 기업 전환청구권  행사 공시", "반도체 주식 수 변동을 공시했다."),
                        news("agency-promotion", "반도체 광고대행사 투자 행사 개최", "증권사가 반도체 체험 행사 참가자를 모집한다."),
                        news(
                                "rights-promotion",
                                "반도체 신주인수권 행사 설명회 개최",
                                "증권사가 반도체 금융 설명회 참가자를 모집한다."));
        when(source.articles(any(Context.class))).thenReturn(rows);
        when(source.cachedArticles(any(Context.class))).thenReturn(Optional.of(rows));
        when(source.snapshot(any()))
                .thenReturn(new NewsProvider.Snapshot("day", () -> rows, () -> Optional.of(rows)));
        var provider = new ThemeNewsProvider(source, themes);
        assertThat(provider.articles(c))
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights", "agency", "conversion", "conversion-spaced");
        assertThat(provider.cachedArticles(c).orElseThrow())
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights", "agency", "conversion", "conversion-spaced");
        var captured = provider.snapshot(c);
        assertThat(captured.articles().get())
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights", "agency", "conversion", "conversion-spaced");
        assertThat(captured.cachedArticles().get().orElseThrow())
                .extracting(NewsArticle::id)
                .containsExactly("earnings", "rights", "agency", "conversion", "conversion-spaced");
    }

    @Test
    void preservesFinancialTermsAndMarketReportingInsteadOfBanningEventWords() {
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
        var titles =
                List.of(
                        "의결권 행사 논란 주가 급등",
                        "국민연금 의결권 행사 반대",
                        "주주권 행사",
                        "콜옵션 행사",
                        "풋옵션 행사",
                        "교환권 행사",
                        "상환권 행사",
                        "경영권 행사",
                        "워런트 행사",
                        "행사가격 하향 조정",
                        "행사가액 조정 공시",
                        "여행사 주가 급등",
                        "PF 시행사 부도에 건설주 급락",
                        "발행사 신용등급 하향",
                        "운행사 지분 매각",
                        "기업설명회(IR) 개최 후 주가 상승",
                        "투자설명회서 실적 가이던스",
                        "코스닥 상장 기념식",
                        "FOMC 이벤트 앞두고 증시 관망",
                        "이벤트 드리븐 전략",
                        "다보스 포럼서 증시 전망",
                        "세미나서 금리 전망");
        var rows = new java.util.ArrayList<NewsArticle>();
        for (int i = 0; i < titles.size(); i++)
            rows.add(news("keep-" + i, "반도체 " + titles.get(i), "반도체 관련 증권시장 분석이다."));
        var expected = rows.stream().map(NewsArticle::id).toList();
        rows.add(news("contest", "반도체 창업공모전 개최", "반도체 증권 공모전 모집"));
        rows.add(news("promo", "반도체 고객 초청 행사", "반도체 증권 고객 초청 행사"));
        rows.add(news("listed-etf-promo", "반도체 상장 ETF 거래 이벤트 경품 증정", "반도체 증권 고객 경품 이벤트다."));
        rows.add(news("market-seminar-ad", "반도체 주가 전망 세미나 참가자 모집", "반도체 증권 투자 세미나 모집 안내다."));
        rows.add(news("market-seminar-entry", "반도체 증시 전망 세미나 신청자 모집", "반도체 증권 세미나 신청 안내다."));
        when(source.articles(any(Context.class))).thenReturn(rows);
        when(source.cachedArticles(any(Context.class))).thenReturn(Optional.of(rows));
        when(source.snapshot(any()))
                .thenReturn(new NewsProvider.Snapshot("day", () -> rows, () -> Optional.of(rows)));
        var provider = new ThemeNewsProvider(source, themes);
        assertThat(provider.articles(c))
                .extracting(NewsArticle::id)
                .containsExactlyElementsOf(expected);
        assertThat(provider.cachedArticles(c).orElseThrow())
                .extracting(NewsArticle::id)
                .containsExactlyElementsOf(expected);
        assertThat(provider.snapshot(c).articles().get())
                .extracting(NewsArticle::id)
                .containsExactlyElementsOf(expected);
        assertThat(provider.id()).isEqualTo("THEME_FREE_NEWS_V2_STOCK_MARKET");
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
