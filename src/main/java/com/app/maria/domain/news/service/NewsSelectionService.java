package com.app.maria.domain.news.service;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.*;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NewsSelectionService {
    public static final String RULE_VERSION =
            "holdings100-importance10-risk-adjusted-profile-v3-latest-display";

    public List<Selected> select(Context context, List<NewsArticle> candidates) {
        return candidates.stream()
                .filter(a -> a.tickers().stream().anyMatch(context.tickers()::contains))
                .map(a -> new Selected(a, score(context, a)))
                .sorted(
                        Comparator.comparingInt(Selected::score)
                                .reversed()
                                .thenComparing(
                                        s -> s.article().publishedAt(), Comparator.reverseOrder())
                                .thenComparing(s -> s.article().id()))
                .limit(8)
                // Preserve personalized membership; present selected articles newest first.
                // Stable sorting retains score/id tie-breakers for identical publication times.
                .sorted(
                        Comparator.comparing(
                                s -> s.article().publishedAt(), Comparator.reverseOrder()))
                .toList();
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
