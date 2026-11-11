package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsSelectionServiceTest {
    private final NewsSelectionService service = new NewsSelectionService();

    static NewsArticle article(String id, int stable, int growth) {
        return new NewsArticle(
                id,
                "v1",
                "시연",
                "사실 위험",
                "MARIA",
                null,
                LocalDateTime.of(2026, 11, 5, 9, 0),
                List.of("005930"),
                List.of("사실"),
                List.of("위험"),
                5,
                stable,
                growth);
    }

    static Context context(InvestorType type) {
        return new Context(1L, Set.of("005930"), type, PersonalizationStatus.ACTIVE);
    }

    @Test
    void scoresFollowWeightsAndAreReproducibleAcrossCandidateOrder() {
        var a = article("a", 8, 3);
        var b = article("b", 3, 8);
        for (var type : InvestorType.values()) {
            var first = service.select(context(type), List.of(a, b));
            assertThat(first).isEqualTo(service.select(context(type), List.of(b, a)));
        }
        assertThat(service.score(context(InvestorType.STABLE), a)).isEqualTo(182);
        assertThat(service.score(context(InvestorType.AGGRESSIVE), a)).isEqualTo(162);
        assertThat(service.select(context(InvestorType.STABLE), List.of(a, b)).get(0).article())
                .isEqualTo(a);
        assertThat(service.select(context(InvestorType.AGGRESSIVE), List.of(a, b)).get(0).article())
                .isEqualTo(b);
    }

    @Test
    void differentProfilesCanHaveSameOrderAndAlwaysShareFactsAndRisks() {
        var a = article("a", 5, 5);
        var b = article("b", 5, 5);
        assertThat(service.select(context(InvestorType.STABLE), List.of(b, a)))
                .isEqualTo(service.select(context(InvestorType.AGGRESSIVE), List.of(a, b)));
        assertThat(
                        service.select(context(InvestorType.STABLE), List.of(a))
                                .get(0)
                                .article()
                                .risks())
                .containsExactly("위험");
    }

    @Test
    void selectedArticlesDisplayNewestFirstEvenWhenOlderArticleScoresHigher() {
        var old = article("old", 10, 0);
        var base = article("new", 0, 0);
        var recent =
                new NewsArticle(
                        base.id(),
                        base.version(),
                        base.title(),
                        base.body(),
                        base.source(),
                        base.url(),
                        old.publishedAt().plusDays(1),
                        base.tickers(),
                        base.facts(),
                        base.risks(),
                        base.importance(),
                        base.stability(),
                        base.growth());
        var selected = service.select(context(InvestorType.STABLE), List.of(old, recent));
        assertThat(selected).extracting(s -> s.article().id()).containsExactly("new", "old");
        assertThat(selected.get(1).score()).isGreaterThan(selected.get(0).score());
        assertThat(service.select(context(InvestorType.STABLE), List.of(recent, old)))
                .isEqualTo(selected);
    }

    @Test
    void latestArticleIsSelectedAheadOfOlderHigherScores() {
        var candidates = new ArrayList<NewsArticle>();
        for (int i = 0; i < 8; i++) candidates.add(article("selected-" + i, 10, 10));
        var base = article("low-score-newest", 0, 0);
        candidates.add(
                new NewsArticle(
                        base.id(),
                        base.version(),
                        base.title(),
                        base.body(),
                        base.source(),
                        base.url(),
                        base.publishedAt().plusDays(1),
                        base.tickers(),
                        base.facts(),
                        base.risks(),
                        base.importance(),
                        base.stability(),
                        base.growth()));
        assertThat(service.select(context(InvestorType.STABLE), candidates))
                .hasSize(8)
                .anyMatch(s -> s.article().id().equals("low-score-newest"));
    }

    @Test
    void nonHeldStockIsExcluded() {
        assertThat(
                        service.select(
                                new Context(
                                        2L,
                                        Set.of(),
                                        InvestorType.ACTIVE,
                                        PersonalizationStatus.ACTIVE),
                                List.of(article("a", 5, 5))))
                .isEmpty();
    }
}
