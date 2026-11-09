package com.app.maria.domain.news.provider;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.*;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.service.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Shared searches are by database theme, never company name. Projection restores only owned links.
 */
@Component
@Primary
@ConditionalOnProperty(name = "news.provider", havingValue = "free")
public class ThemeNewsProvider implements NewsProvider {
    private final FreeNewsProvider source;
    private final NewsThemeService themes;

    public ThemeNewsProvider(FreeNewsProvider source, NewsThemeService themes) {
        this.source = source;
        this.themes = themes;
    }

    public String id() {
        return "THEME_FREE_NEWS_V1";
    }

    public List<String> themeNames(Context c) {
        return themes.themes(c.tickers()).values().stream()
                .flatMap(Set::stream)
                .distinct()
                .sorted()
                .toList();
    }

    public List<NewsArticle> articles() {
        return List.of();
    }

    private Context queries(Context c) {
        var names = new TreeMap<String, String>();
        themes.themes(c.tickers())
                .values()
                .forEach(values -> values.forEach(t -> names.put("theme:" + t, t)));
        return new Context(
                0L, names.keySet(), InvestorType.NEUTRAL, PersonalizationStatus.ACTIVE, names);
    }

    public void configureInventory(Context c) {
        source.inventorySize(queries(c).tickers().size());
    }

    public String contextVersion(Context c) {
        return NewsCacheIdentity.hash(themes.themes(c.tickers()).toString());
    }

    public List<NewsArticle> articles(Context c) {
        var q = queries(c);
        if (q.tickers().isEmpty()) return List.of();
        return project(source.articles(q), c);
    }

    public Optional<List<NewsArticle>> cachedArticles(Context c) {
        var q = queries(c);
        if (q.tickers().isEmpty()) return Optional.of(List.of());
        return source.cachedArticles(q).map(rows -> project(rows, c));
    }

    private List<NewsArticle> project(List<NewsArticle> rows, Context c) {
        var mapping = themes.themes(c.tickers());
        return rows.stream()
                .map(
                        a -> {
                            var owned =
                                    mapping.entrySet().stream()
                                            .filter(
                                                    e ->
                                                            e.getValue().stream()
                                                                    .anyMatch(
                                                                            t ->
                                                                                    a.tickers()
                                                                                            .contains(
                                                                                                    "theme:"
                                                                                                            + t)))
                                            .map(Map.Entry::getKey)
                                            .sorted()
                                            .toList();
                            return new NewsArticle(
                                    a.id(),
                                    a.version(),
                                    a.title(),
                                    a.body(),
                                    a.source(),
                                    a.url(),
                                    a.publishedAt(),
                                    owned,
                                    a.facts(),
                                    a.risks(),
                                    a.importance(),
                                    a.stability(),
                                    a.growth(),
                                    a.demo(),
                                    themes.mentionedStocks(a));
                        })
                .filter(a -> !a.tickers().isEmpty())
                .toList();
    }
}
