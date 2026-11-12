package com.app.maria.domain.news.service;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NewsSelectionService {
    public static final String RULE_VERSION = "theme-balanced-business-order-first-v9";

    private static final int LIMIT = 8;

    public List<Selected> select(Context context, List<NewsArticle> candidates) {
        var themePriorities = context.themePriorities();
        Comparator<Selected> newest =
                Comparator.comparing(
                                (Selected s) -> s.article().publishedAt(),
                                Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingInt(Selected::score).reversed())
                        .thenComparing(s -> s.article().id());
        // Merge theme-search projections only for the same article revision.
        Map<String, List<NewsArticle>> revisions = new TreeMap<>();
        candidates.stream()
                .filter(a -> a.tickers().stream().anyMatch(context.tickers()::contains))
                .forEach(
                        a ->
                                revisions
                                        .computeIfAbsent(a.id(), ignored -> new ArrayList<>())
                                        .add(a));
        Map<String, Selected> unique = new TreeMap<>();
        // A revision identifies immutable article content; only projected links are merged.
        Comparator<Selected> revisionOrder = newest.thenComparing(s -> s.article().version());
        for (var copies : revisions.values()) {
            var base =
                    copies.stream()
                            .map(a -> new Selected(a, score(context, a)))
                            .min(revisionOrder)
                            .orElseThrow()
                            .article();
            var matching =
                    copies.stream()
                            .filter(
                                    a ->
                                            a.version().equals(base.version())
                                                    && a.publishedAt().equals(base.publishedAt()))
                            .toList();
            var tickers = new TreeSet<String>();
            var themes = new TreeSet<String>();
            matching.forEach(
                    a -> {
                        tickers.addAll(a.tickers());
                        themes.addAll(a.selectionThemes());
                    });
            var merged =
                    new NewsArticle(
                            base.id(),
                            base.version(),
                            base.title(),
                            base.body(),
                            base.source(),
                            base.url(),
                            base.publishedAt(),
                            List.copyOf(tickers),
                            base.facts(),
                            base.risks(),
                            base.importance(),
                            base.stability(),
                            base.growth(),
                            base.demo(),
                            base.relatedStocks(),
                            List.copyOf(themes));
            unique.put(merged.id(), new Selected(merged, score(context, merged)));
        }
        Comparator<Selected> displayOrder =
                Comparator.comparingLong(
                                (Selected s) ->
                                        s.article().selectionThemes().stream()
                                                .mapToLong(
                                                        theme ->
                                                                themePriorities.getOrDefault(
                                                                        theme, 0L))
                                                .max()
                                                .orElse(0))
                        .reversed()
                        .thenComparing(newest);
        var ranked = unique.values().stream().sorted(newest).toList();
        Map<String, List<Selected>> buckets = new TreeMap<>();
        ranked.stream()
                .forEach(
                        s -> {
                            var themes = s.article().selectionThemes();
                            for (String theme : new TreeSet<>(themes)) {
                                if (theme.isBlank()) continue;
                                buckets.computeIfAbsent(theme, ignored -> new ArrayList<>()).add(s);
                            }
                        });
        // Unclassified articles do not consume a theme quota. Non-theme providers use latest-first.
        if (buckets.isEmpty()) return ranked.stream().limit(LIMIT).toList();
        var ordered =
                buckets.entrySet().stream()
                        .sorted(
                                (a, b) -> {
                                    int priority =
                                            Long.compare(
                                                    themePriorities.getOrDefault(b.getKey(), 0L),
                                                    themePriorities.getOrDefault(a.getKey(), 0L));
                                    if (priority != 0) return priority;
                                    int comparison =
                                            newest.compare(
                                                    a.getValue().get(0), b.getValue().get(0));
                                    return comparison != 0
                                            ? comparison
                                            : a.getKey().compareTo(b.getKey());
                                })
                        .toList();
        Map<String, Selected> picked = new LinkedHashMap<>();
        int quota = Math.max(1, LIMIT / buckets.size());
        // Fair allocation; newly held themes win slots first when there are more than eight.
        for (int round = 0; round < quota && picked.size() < LIMIT; round++) {
            for (var bucket : ordered) {
                if (picked.size() == LIMIT) break;
                // A shared article already represents every matching theme. Do not replace it
                // with an older article just to count the same coverage again.
                long covered =
                        bucket.getValue().stream()
                                .filter(s -> picked.containsKey(s.article().id()))
                                .count();
                if (covered >= round + 1) continue;
                bucket.getValue().stream()
                        .filter(s -> !picked.containsKey(s.article().id()))
                        .findFirst()
                        .ifPresent(s -> picked.put(s.article().id(), s));
            }
        }
        // Remainder and undersupplied quotas go to the newest remaining unique articles.
        ranked.stream()
                .filter(s -> !picked.containsKey(s.article().id()))
                .limit(LIMIT - picked.size())
                .forEach(s -> picked.put(s.article().id(), s));
        return picked.values().stream().sorted(displayOrder).toList();
    }

    int score(Context c, NewsArticle a) {
        long related = a.tickers().stream().distinct().filter(c.tickers()::contains).count();
        int stableWeight =
                switch (c.investorType()) {
                    case STABLE -> 4;
                    case CONSERVATIVE -> 3;
                    case NEUTRAL, UNASSIGNED -> 2;
                    case ACTIVE -> 1;
                    case AGGRESSIVE -> 0;
                };
        return (int) related * 100
                + a.importance() * 10
                + a.stability() * stableWeight
                + a.growth() * (4 - stableWeight);
    }
}
