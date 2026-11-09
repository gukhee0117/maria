package com.app.maria.domain.news.provider;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.*;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.service.*;
import java.util.*;
import java.util.regex.Pattern;
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
    private static final Pattern STOCK_MARKET =
            Pattern.compile(
                    "주가|주식(?!회사)|증권|증시|코스피|코스닥|유가증권|상장|공모주|시가총액|목표주가|투자의견|자사주|유상증자|액면분할|주당(?:순이익|배당)|배당(?:금|수익률|정책|주)|\\b(?:IPO|ETF|ETN|PER|PBR|EPS)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE_FOOTER = Pattern.compile("※|ⓒ|무단\\s*(?:전재|복제)|저작권자");
    private final FreeNewsProvider source;
    private final NewsThemeService themes;

    public ThemeNewsProvider(FreeNewsProvider source, NewsThemeService themes) {
        this.source = source;
        this.themes = themes;
    }

    public String id() {
        return "THEME_FREE_NEWS_V2_STOCK_MARKET";
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

    private boolean isThemeStockMarketArticle(NewsArticle article, Map<String, String> allThemes) {
        // Do not let publisher footers or investment disclaimers turn a general story into stock
        // news.
        String body = ARTICLE_FOOTER.split(article.body(), 2)[0];
        String text = article.title() + " " + body;
        if (!STOCK_MARKET.matcher(text).find()) return false;
        String normalized = text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
        return allThemes.entrySet().stream()
                .anyMatch(
                        theme ->
                                article.tickers().contains("theme:" + theme.getKey())
                                        && normalized.contains(theme.getValue()));
    }

    private List<NewsArticle> project(List<NewsArticle> rows, Context c) {
        var mapping = themes.themes(c.tickers());
        Map<String, String> allThemes = new HashMap<>();
        mapping.values()
                .forEach(
                        values ->
                                values.forEach(
                                        theme ->
                                                allThemes.putIfAbsent(
                                                        theme,
                                                        theme.replaceAll("[^\\p{L}\\p{N}]", "")
                                                                .toLowerCase(Locale.ROOT))));
        return rows.stream()
                .filter(a -> isThemeStockMarketArticle(a, allThemes))
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
