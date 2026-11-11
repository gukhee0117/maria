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
    private static final Pattern NORMALIZE_TEXT = Pattern.compile("[^\\p{L}\\p{N}]");

    private static String normalize(String text) {
        return NORMALIZE_TEXT.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static final Pattern STOCK_MARKET =
            Pattern.compile(
                    "주가|주식(?!회사)|증권|증시|코스피|코스닥|유가증권|상장|공모주|시가총액|목표주가|투자의견|자사주|유상증자|액면분할|주당(?:순이익|배당)|배당(?:금|수익률|정책|주)|\\b(?:IPO|ETF|ETN|PER|PBR|EPS)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final String FILTER_VERSION = "promotion-v6-explicit-advertising";
    private static final Pattern CONTEST = Pattern.compile("공모전(?!략)|경진대회|박람회|페스티벌|체험전");
    private static final Pattern PROMOTIONAL_EVENT =
            Pattern.compile("(?<![가-힣])행사(?!가)|(?:체험|홍보|기념|고객|초청)\\s*행사|이벤트|세미나|컨퍼런스|포럼|설명회|기념식");
    private static final Pattern PROMOTION =
            Pattern.compile("개최|모집|참가|경품|증정|초청|응모|접수|개막|성료|진행|거래\\s*이벤트|체험\\s*행사|투자\\s*세미나");
    private static final Pattern EXPLICIT_ADVERTISING =
            Pattern.compile("경품|증정|응모|접수|(?:참가자|참가팀|참가기업|참가업체|수강생|신청자|고객)\\s*모집");
    private static final Pattern MARKET_TITLE =
            Pattern.compile(
                    "주가|증시|코스피|코스닥|상장|실적|가이던스|금리|신용등급|부도|지분\\s*매각|행사가[격액]|FOMC|이벤트\\s*드리븐",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern FINANCIAL_EXERCISE =
            Pattern.compile("(?:[가-힣]*권|[가-힣]*옵션|워런트|권리)\\s*행사");
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

    @Override
    public Snapshot snapshot(Context c) {
        var q = queries(c);
        if (q.tickers().isEmpty())
            return new Snapshot(id(), List::of, () -> Optional.of(List.of()));
        var sourceSnapshot = source.snapshot(q);
        String version =
                NewsCacheIdentity.hash(
                        FILTER_VERSION
                                + ":"
                                + id()
                                + ":"
                                + themes.themes(c.tickers()).toString()
                                + ":"
                                + sourceSnapshot.version());
        return new Snapshot(
                version,
                () -> project(sourceSnapshot.articles().get(), c),
                () -> sourceSnapshot.cachedArticles().get().map(rows -> project(rows, c)));
    }

    private List<String> matchedThemes(NewsArticle article, Map<String, String> allThemes) {
        // Do not let publisher footers or investment disclaimers turn a general story into stock
        // news.
        String title = FINANCIAL_EXERCISE.matcher(article.title()).replaceAll("");
        if (CONTEST.matcher(title).find()) return List.of();
        // A market-related event topic does not turn a participation advert into market reporting.
        if (PROMOTIONAL_EVENT.matcher(title).find() && EXPLICIT_ADVERTISING.matcher(title).find())
            return List.of();
        if (PROMOTIONAL_EVENT.matcher(title).find()
                && PROMOTION.matcher(title).find()
                && !MARKET_TITLE.matcher(title).find()) return List.of();
        String body = ARTICLE_FOOTER.split(article.body(), 2)[0];
        String text = article.title() + " " + body;
        if (!STOCK_MARKET.matcher(text).find()) return List.of();
        String normalized = normalize(text);
        return allThemes.entrySet().stream()
                .filter(
                        theme ->
                                article.tickers().contains("theme:" + theme.getKey())
                                        && normalized.contains(theme.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private List<NewsArticle> project(List<NewsArticle> rows, Context c) {
        var mapping = themes.themes(c.tickers());
        Map<String, String> allThemes = new HashMap<>();
        mapping.values()
                .forEach(
                        values ->
                                values.forEach(
                                        theme -> allThemes.putIfAbsent(theme, normalize(theme))));
        return rows.stream()
                .flatMap(
                        a -> {
                            var matchedThemes = matchedThemes(a, allThemes);
                            if (matchedThemes.isEmpty())
                                return java.util.stream.Stream.<NewsArticle>empty();
                            var owned =
                                    mapping.entrySet().stream()
                                            .filter(
                                                    e ->
                                                            e.getValue().stream()
                                                                    .anyMatch(
                                                                            matchedThemes
                                                                                    ::contains))
                                            .map(Map.Entry::getKey)
                                            .sorted()
                                            .toList();
                            return java.util.stream.Stream.of(
                                    new NewsArticle(
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
                                            themes.mentionedStocks(a),
                                            matchedThemes));
                        })
                .filter(a -> !a.tickers().isEmpty())
                .toList();
    }
}
