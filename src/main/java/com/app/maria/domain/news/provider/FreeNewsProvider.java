package com.app.maria.domain.news.provider;

import com.app.maria.domain.news.client.FreeNewsClient;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.Context;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "news.provider", havingValue = "free")
public class FreeNewsProvider implements NewsProvider {
    private static final Pattern RISK = Pattern.compile("위험|우려|불확실|둔화|감소|하락|손실|부진|급락|비용 증가");
    private static final Pattern GROWTH = Pattern.compile("성장|확대|증가|신제품|투자|상승|개선|협력");
    private static final Pattern FINANCIAL_TITLE =
            Pattern.compile("반도체|실적|매출|영업이익|주가|증시|급락|급등|사업|투자|배당|수주|생산|공급|수요");

    private record Cached(List<NewsArticle> articles, Instant expiresAt) {}

    private final FreeNewsClient client;
    private final Clock clock;
    private final com.app.maria.domain.news.config.NewsProperties properties;
    private volatile int inventorySize = 1;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<List<NewsArticle>>> inFlight =
            new ConcurrentHashMap<>();
    private final ExecutorService workers =
            Executors.newFixedThreadPool(
                    2,
                    task -> {
                        var thread = new Thread(task, "news-source-reader");
                        thread.setDaemon(true);
                        return thread;
                    });

    public FreeNewsProvider(FreeNewsClient client) {
        this(client, new com.app.maria.domain.news.config.NewsProperties());
    }

    @Autowired
    public FreeNewsProvider(
            FreeNewsClient client, com.app.maria.domain.news.config.NewsProperties properties) {
        this(client, Clock.systemUTC(), properties);
    }

    FreeNewsProvider(FreeNewsClient client, Clock clock) {
        this(client, clock, new com.app.maria.domain.news.config.NewsProperties());
    }

    FreeNewsProvider(
            FreeNewsClient client,
            Clock clock,
            com.app.maria.domain.news.config.NewsProperties properties) {
        this.client = client;
        this.clock = clock;
        this.properties = properties;
    }

    public String id() {
        return "FREE_NEWS_V1";
    }

    public List<NewsArticle> articles() {
        return List.of();
    }

    @Override
    public List<NewsArticle> articles(Context context) {
        var pending =
                context.tickers().stream()
                        .sorted()
                        .filter(
                                t ->
                                        context.stockNames().get(t) != null
                                                && !context.stockNames().get(t).isBlank())
                        .map(t -> prepare(t, context.stockNames().get(t)))
                        .toList();
        var lists = new ArrayList<List<NewsArticle>>();
        for (var future : pending) {
            try {
                lists.add(future.join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException failure) throw failure;
                throw e;
            }
        }
        return merge(lists);
    }

    @Override
    public Optional<List<NewsArticle>> cachedArticles(Context context) {
        var lists = new ArrayList<List<NewsArticle>>();
        for (var ticker : context.tickers().stream().sorted().toList()) {
            var name = context.stockNames().get(ticker);
            if (name == null || name.isBlank()) continue;
            var entry = cache.get(ticker + ":" + name);
            if (entry == null || !entry.expiresAt().isAfter(clock.instant()))
                return Optional.empty();
            lists.add(entry.articles());
        }
        return Optional.of(merge(lists));
    }

    public void inventorySize(int size) {
        inventorySize = Math.max(1, size);
    }

    @Override
    public void configureInventory(Context context) {
        inventorySize(context.tickers().size());
    }

    private List<NewsArticle> merge(List<List<NewsArticle>> lists) {
        Map<String, NewsArticle> unique = new LinkedHashMap<>();
        for (var articles : lists) {
            for (var article : articles) {
                // Track both stable canonical URLs and identical bodies
                // (tracking/syndication).
                String key = hash(article.body().replaceAll("\\s+", " "));
                unique.merge(
                        key,
                        article,
                        (a, b) -> {
                            var tickers = new TreeSet<>(a.tickers());
                            tickers.addAll(b.tickers());
                            return new NewsArticle(
                                    a.id(),
                                    a.version(),
                                    a.title(),
                                    a.body(),
                                    a.source(),
                                    a.url(),
                                    a.publishedAt(),
                                    List.copyOf(tickers),
                                    a.facts(),
                                    a.risks(),
                                    a.importance(),
                                    a.stability(),
                                    a.growth(),
                                    false);
                        });
            }
        }
        return List.copyOf(unique.values());
    }

    private List<NewsArticle> load(String ticker, String name) {
        String key = ticker + ":" + name;
        var stored = cache.get(key);
        if (stored != null && stored.expiresAt().isAfter(clock.instant())) return stored.articles();
        var result =
                cache.compute(
                        key,
                        (ignored, current) -> {
                            if (current != null && current.expiresAt().isAfter(clock.instant()))
                                return current;
                            return fetch(ticker, name);
                        });
        cache.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(clock.instant()));
        if (cache.size() > 128)
            cache.keySet().stream()
                    .filter(k -> !k.equals(key))
                    .findFirst()
                    .ifPresent(cache::remove);
        return result.articles();
    }

    private CompletableFuture<List<NewsArticle>> prepare(String ticker, String name) {
        String key = ticker + ":" + name;
        var ready = cache.get(key);
        if (ready != null && ready.expiresAt().isAfter(clock.instant()))
            return CompletableFuture.completedFuture(ready.articles());
        var task =
                inFlight.computeIfAbsent(
                        key,
                        ignored ->
                                CompletableFuture.supplyAsync(() -> load(ticker, name), workers));
        task.whenComplete((value, error) -> inFlight.remove(key, task));
        return task;
    }

    private Cached fetch(String ticker, String name) {
        List<NewsArticle> result = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        Set<String> bodies = new HashSet<>();
        for (JsonNode item : client.search(name)) {
            NewsArticle article = map(item, ticker, name);
            if (article != null
                    && urls.add(article.url())
                    && bodies.add(hash(article.body().replaceAll("\\s+", " "))))
                result.add(article);
        }
        // Empty successes also obey the collection interval; failures never become empty news.
        var immutable = List.copyOf(result);
        return new Cached(
                immutable,
                clock.instant()
                        .plusSeconds(
                                Math.max(
                                        properties.getSourceCacheTtl().toSeconds(),
                                        (long)
                                                Math.ceil(
                                                        inventorySize
                                                                * Duration.ofDays(31).toSeconds()
                                                                / (double)
                                                                        Math.max(
                                                                                1,
                                                                                properties
                                                                                        .getSourceMonthlyRequestLimit())))));
    }

    @PreDestroy
    public void close() {
        inFlight.values().forEach(task -> task.cancel(true));
        workers.shutdownNow();
    }

    private NewsArticle map(JsonNode item, String ticker, String stockName) {
        String title = item.path("title").asText("").strip();
        String body = item.path("text").asText("").strip();
        if (!item.path("lang").asText().equals("ko")
                || !item.path("country").asText().equals("KR")
                || body.length() < 100
                || body.length() > 24000
                || title.isBlank()
                || !(normalize(title).contains(normalize(stockName))
                        || (normalize(body).contains(normalize(stockName))
                                && FINANCIAL_TITLE.matcher(title).find()))) return null;
        try {
            String url = canonicalUrl(item.path("url").asText());
            if (url == null) return null;
            var published =
                    OffsetDateTime.parse(item.path("published_at").asText())
                            .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
                            .toLocalDateTime();
            String source = item.path("sitename").asText("");
            if (source.isBlank()) source = URI.create(url).getHost();
            String version = hash(title + "\n" + source + "\n" + body);
            List<String> sentences =
                    Arrays.stream(body.split("(?<=[.!?。])\\s+|\\n+"))
                            .map(String::strip)
                            .filter(s -> s.length() >= 10 && s.length() <= 180)
                            .toList();
            List<String> facts =
                    sentences.isEmpty() ? List.of(title) : sentences.stream().limit(1).toList();
            List<String> risks =
                    sentences.stream().filter(s -> RISK.matcher(s).find()).limit(1).toList();
            int stable = Math.min(10, (int) RISK.matcher(title + " " + body).results().count());
            int growth = Math.min(10, (int) GROWTH.matcher(title + " " + body).results().count());
            return new NewsArticle(
                    "free-" + hash(url),
                    version,
                    title,
                    body,
                    source,
                    url,
                    published,
                    List.of(ticker),
                    facts,
                    risks,
                    5,
                    stable,
                    growth,
                    false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String normalize(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    private String canonicalUrl(String input) {
        var uri = URI.create(input);
        if (!Set.of("https", "http").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) return null;
        String query = uri.getRawQuery();
        if (query != null)
            query =
                    Arrays.stream(query.split("&"))
                            .filter(
                                    p ->
                                            !p.toLowerCase(Locale.ROOT)
                                                    .matches(
                                                            "(?:utm_[^=]*|outurl|fbclid|gclid)=.*"))
                            .sorted()
                            .collect(java.util.stream.Collectors.joining("&"));
        return uri.getScheme()
                + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                + uri.getRawPath()
                + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
