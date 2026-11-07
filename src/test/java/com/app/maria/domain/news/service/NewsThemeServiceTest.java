package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsThemeLink;
import com.app.maria.domain.news.mapper.NewsInventoryMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsThemeServiceTest {
    @Test
    void relatedStocksComeOnlyFromCompaniesMentionedInArticleAndResolveCommonAlias() {
        var mapper = mock(NewsInventoryMapper.class);
        when(mapper.selectThemeLinks())
                .thenReturn(
                        List.of(
                                link("005930", "삼성전자", "전기·전자"),
                                link("000660", "SK하이닉스", "전기·전자"),
                                link("035420", "NAVER", "서비스업"),
                                link("005380", "현대차", "운송장비·부품")));
        var service = new NewsThemeService(mapper);
        var article =
                new NewsArticle(
                        "a",
                        "v1",
                        "네이버, SK하이닉스와 AI 협력",
                        "네이버와 SK하이닉스가 AI 사업 협력을 발표했다.",
                        "source",
                        "https://example.com",
                        LocalDateTime.now(),
                        List.of("theme:반도체"),
                        List.of("협력 사실"),
                        List.of("위험"),
                        5,
                        5,
                        5,
                        false);

        assertThat(service.mentionedStocks(article))
                .containsExactly(
                        new com.app.maria.domain.news.dto.NewsStockReference(
                                "000660", "SK하이닉스", "전기·전자"),
                        new com.app.maria.domain.news.dto.NewsStockReference(
                                "035420", "NAVER", "서비스업"));
    }

    private NewsThemeLink link(String ticker, String name, String sector) {
        var link = new NewsThemeLink();
        link.setTicker(ticker);
        link.setName(name);
        link.setSectorName(sector);
        return link;
    }
}
