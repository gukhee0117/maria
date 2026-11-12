package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.Selected;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsBalancedSelectionTest {
    private final NewsSelectionService service = new NewsSelectionService();
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 2, 9, 0);

    private NewsArticle article(String id, int minutes, int importance, String... themes) {
        return new NewsArticle(
                id,
                "v1",
                id,
                "body",
                "source",
                null,
                start.plusMinutes(minutes),
                List.of("005930"),
                List.of(),
                List.of(),
                importance,
                0,
                0,
                false,
                List.of(),
                List.of(themes));
    }

    private List<Selected> select(List<NewsArticle> rows) {
        return service.select(NewsSelectionServiceTest.context(InvestorType.STABLE), rows);
    }

    private long count(List<Selected> rows, String theme) {
        return rows.stream().filter(s -> s.article().selectionThemes().contains(theme)).count();
    }

    @Test
    void twoThemesGetFourEachEvenWhenOneThemeIsOlderAndScoresLower() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 10; i++) {
            rows.add(article("chip-" + i, 100 + i, 10, "chip"));
            rows.add(article("tel-" + i, i, 0, "telecom"));
        }
        var selected = select(rows);
        assertThat(selected).hasSize(8);
        assertThat(count(selected, "chip")).isEqualTo(4);
        assertThat(count(selected, "telecom")).isEqualTo(4);
        assertThat(selected)
                .extracting(s -> s.article().id())
                .containsExactly(
                        "chip-9", "chip-8", "chip-7", "chip-6", "tel-9", "tel-8", "tel-7", "tel-6");
        Collections.reverse(rows);
        assertThat(select(rows)).isEqualTo(selected);
    }

    @Test
    void threeThemesGetTwoEachThenNewestRemainingArticlesFillTheRemainder() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 6; i++) {
            rows.add(article("a" + i, 100 + i, 0, "a"));
            rows.add(article("b" + i, 50 + i, 10, "b"));
            rows.add(article("c" + i, i, 10, "c"));
        }
        var selected = select(rows);
        assertThat(count(selected, "a")).isEqualTo(4);
        assertThat(count(selected, "b")).isEqualTo(2);
        assertThat(count(selected, "c")).isEqualTo(2);
    }

    @Test
    void shortThemeReturnsItsOneArticleAndOtherThemeFillsRemainingSeven() {
        var rows = new ArrayList<NewsArticle>();
        rows.add(article("only-tel", 0, 0, "telecom"));
        for (int i = 0; i < 10; i++) rows.add(article("chip" + i, i, 10, "chip"));
        var selected = select(rows);
        assertThat(selected).hasSize(8);
        assertThat(count(selected, "telecom")).isEqualTo(1);
        assertThat(count(selected, "chip")).isEqualTo(7);
    }

    @Test
    void moreThanEightThemesChooseTheNewestRepresentativesDespiteScore() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 10; i++) rows.add(article("a" + i, i, 10 - i, "theme" + i));
        assertThat(select(rows))
                .extracting(s -> s.article().id())
                .containsExactly("a9", "a8", "a7", "a6", "a5", "a4", "a3", "a2");
    }

    @Test
    void sameArticleAcrossThemesAndRepeatedSearchResultsOnlyUsesOneSlot() {
        var shared = article("shared", 100, 10, "a", "b");
        var rows = new ArrayList<NewsArticle>(List.of(shared, shared));
        for (int i = 0; i < 8; i++) {
            rows.add(article("a" + i, i, 0, "a"));
            rows.add(article("b" + i, i, 0, "b"));
        }
        assertThat(select(rows))
                .hasSize(8)
                .extracting(s -> s.article().id())
                .doesNotHaveDuplicates()
                .contains("shared");
        assertThat(select(List.of(shared, shared))).hasSize(1);
    }

    @Test
    void sharedRepresentativeDoesNotPromoteOldSubstituteOverAnotherThemesLatestArticle() {
        var rows = new ArrayList<NewsArticle>();
        rows.add(article("shared", 100, 10, "a", "b"));
        rows.add(article("old-b", -100, 10, "b"));
        for (int i = 0; i < 7; i++) rows.add(article("recent-" + i, i, 0, "theme-" + i));
        var selected = select(rows);
        assertThat(selected)
                .extracting(s -> s.article().id())
                .containsExactly(
                        "shared",
                        "recent-6",
                        "recent-5",
                        "recent-4",
                        "recent-3",
                        "recent-2",
                        "recent-1",
                        "recent-0");
        Collections.reverse(rows);
        assertThat(select(rows)).isEqualTo(selected);
    }

    @Test
    void duplicateSearchProjectionsMergeThemesIndependentlyOfInputOrder() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 8; i++) rows.add(article("chip-" + i, 20 + i, 10, "chip"));
        rows.add(article("shared", 0, 0, "chip"));
        rows.add(article("shared", 0, 0, "telecom"));
        var selected = select(rows);
        assertThat(selected)
                .hasSize(8)
                .extracting(s -> s.article().id())
                .doesNotHaveDuplicates()
                .contains("shared");
        assertThat(
                        selected.stream()
                                .filter(s -> s.article().id().equals("shared"))
                                .findFirst()
                                .orElseThrow()
                                .article()
                                .selectionThemes())
                .containsExactly("chip", "telecom");
        Collections.reverse(rows);
        assertThat(select(rows)).isEqualTo(selected);
    }

    @Test
    void olderRevisionDoesNotAddObsoleteThemesToNewerRevision() {
        var old = article("same", 0, 10, "obsolete");
        var latest = article("same", 10, 0, "current");
        assertThat(select(List.of(old, latest)))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.article().publishedAt()).isEqualTo(latest.publishedAt());
                            assertThat(s.article().selectionThemes()).containsExactly("current");
                        });
    }

    @Test
    void emptyCandidatesAreEmptyAndOnlyHeldStocksRemainEligible() {
        assertThat(select(List.of())).isEmpty();
        var a = article("x", 0, 10, "a");
        var unrelated =
                new NewsArticle(
                        a.id(),
                        a.version(),
                        a.title(),
                        a.body(),
                        a.source(),
                        a.url(),
                        a.publishedAt(),
                        List.of("000660"),
                        a.facts(),
                        a.risks(),
                        10,
                        0,
                        0,
                        false,
                        List.of(),
                        List.of("a"));
        assertThat(select(List.of(unrelated))).isEmpty();
    }

    @Test
    void differentProfilesSelectTheSameLatestEightWhenPublicationTimesDiffer() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 10; i++) {
            var a = article("a" + i, i, 5, "chip");
            rows.add(
                    new NewsArticle(
                            a.id(),
                            a.version(),
                            a.title(),
                            a.body(),
                            a.source(),
                            a.url(),
                            a.publishedAt(),
                            a.tickers(),
                            a.facts(),
                            a.risks(),
                            5,
                            i % 2 == 0 ? 10 : 0,
                            i % 2 == 0 ? 0 : 10,
                            false,
                            List.of(),
                            List.of("chip")));
        }
        var stable = service.select(NewsSelectionServiceTest.context(InvestorType.STABLE), rows);
        var aggressive =
                service.select(NewsSelectionServiceTest.context(InvestorType.AGGRESSIVE), rows);
        assertThat(stable)
                .extracting(s -> s.article().id())
                .containsExactly("a9", "a8", "a7", "a6", "a5", "a4", "a3", "a2");
        assertThat(aggressive)
                .extracting(s -> s.article().id())
                .containsExactlyElementsOf(stable.stream().map(s -> s.article().id()).toList());
        assertThat(stable.get(0).score()).isNotEqualTo(aggressive.get(0).score());
    }

    @Test
    void unclassifiedArticlesDoNotConsumeRealThemeQuotas() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 8; i++) {
            rows.add(article("chip" + i, i, 0, "chip"));
            rows.add(article("tel" + i, i, 0, "telecom"));
            rows.add(article("unclassified" + i, 100 + i, 10));
        }
        var selected = select(rows);
        assertThat(count(selected, "chip")).isEqualTo(4);
        assertThat(count(selected, "telecom")).isEqualTo(4);
        assertThat(selected).noneMatch(s -> s.article().selectionThemes().isEmpty());
    }

    @Test
    void equalPublicationTimeUsesExistingScoreThenStableId() {
        assertThat(
                        select(
                                List.of(
                                        article("z", 0, 10, "a"),
                                        article("b", 0, 0, "a"),
                                        article("a", 0, 0, "a"))))
                .extracting(s -> s.article().id())
                .containsExactly("z", "a", "b");
    }

    @Test
    void newlyHeldThemeComesFirstWithoutChangingQuotas() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 10; i++) {
            rows.add(article("chip-" + i, 100 + i, 10, "chip"));
            rows.add(article("tel-" + i, i, 0, "telecom"));
        }
        var result =
                selectWithPriorities(
                        NewsSelectionServiceTest.context(InvestorType.STABLE),
                        rows,
                        Map.of("telecom", 1L));
        assertThat(count(result, "chip")).isEqualTo(4);
        assertThat(count(result, "telecom")).isEqualTo(4);
        assertThat(result)
                .extracting(s -> s.article().id())
                .containsExactly(
                        "tel-9", "tel-8", "tel-7", "tel-6", "chip-9", "chip-8", "chip-7", "chip-6");
        Collections.reverse(rows);
        assertThat(
                        selectWithPriorities(
                                NewsSelectionServiceTest.context(InvestorType.STABLE),
                                rows,
                                Map.of("telecom", 1L)))
                .isEqualTo(result);
    }

    @Test
    void latestAcquisitionWinsAndSharedArticleIsNotDuplicated() {
        var result =
                selectWithPriorities(
                        NewsSelectionServiceTest.context(InvestorType.STABLE),
                        List.of(
                                article("shared", 0, 0, "telecom", "bank"),
                                article("bank", 10, 0, "bank"),
                                article("chip", 100, 10, "chip")),
                        Map.of("telecom", 2L, "bank", 1L));
        assertThat(result)
                .extracting(s -> s.article().id())
                .containsExactly("shared", "bank", "chip");
    }

    @Test
    void newlyHeldThemeGetsAPlaceEvenAboveEightThemes() {
        var rows = new ArrayList<NewsArticle>();
        for (int i = 0; i < 9; i++) rows.add(article("existing-" + i, 100 + i, 10, "theme-" + i));
        rows.add(article("new", 0, 0, "telecom"));
        var result =
                selectWithPriorities(
                        NewsSelectionServiceTest.context(InvestorType.STABLE),
                        rows,
                        Map.of("telecom", 1L));
        assertThat(result).hasSize(8);
        assertThat(result.get(0).article().id()).isEqualTo("new");
        assertThat(result)
                .extracting(s -> s.article().id())
                .doesNotHaveDuplicates()
                .doesNotContain("existing-0", "existing-1");
    }

    private List<Selected> selectWithPriorities(
            com.app.maria.domain.news.dto.NewsContract.Context c,
            List<NewsArticle> rows,
            Map<String, Long> priorities) {
        return service.select(
                new com.app.maria.domain.news.dto.NewsContract.Context(
                        c.customerId(),
                        c.tickers(),
                        c.investorType(),
                        c.personalizationStatus(),
                        c.stockNames(),
                        c.stockSectors(),
                        priorities),
                rows);
    }
}
