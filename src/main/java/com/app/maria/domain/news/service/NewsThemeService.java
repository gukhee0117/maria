package com.app.maria.domain.news.service;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsStockReference;
import com.app.maria.domain.news.dto.NewsThemeLink;
import com.app.maria.domain.news.mapper.NewsInventoryMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NewsThemeService {
    private static final Map<String, List<String>> NAME_ALIASES =
            Map.of(
                    "035420", List.of("네이버"),
                    "005380", List.of("현대자동차"),
                    "000660", List.of("하이닉스"));

    private final NewsInventoryMapper mapper;
    private List<NewsThemeLink> links = List.of();
    private Instant expires = Instant.MIN;

    public NewsThemeService(NewsInventoryMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized List<NewsThemeLink> forTickers(Set<String> tickers) {
        return allLinks().stream().filter(l -> tickers.contains(l.getTicker())).toList();
    }

    public List<NewsStockReference> mentionedStocks(NewsArticle article) {
        var text = normalize(article.title() + " " + article.body());
        var stocks = new TreeMap<String, NewsStockReference>();
        for (var link : allLinks()) {
            var name = link.getName();
            if (name == null || name.isBlank()) continue;
            boolean mentioned =
                    (normalize(name).length() >= 3 && text.contains(normalize(name)))
                            || text.contains(normalize(link.getTicker()))
                            || NAME_ALIASES.getOrDefault(link.getTicker(), List.of()).stream()
                                    .map(NewsThemeService::normalize)
                                    .anyMatch(text::contains);
            if (mentioned)
                stocks.putIfAbsent(
                        link.getTicker(),
                        new NewsStockReference(
                                link.getTicker(), link.getName(), link.getSectorName()));
        }
        return stocks.values().stream().limit(12).toList();
    }

    private synchronized List<NewsThemeLink> allLinks() {
        if (!expires.isAfter(Instant.now())) {
            links = List.copyOf(mapper.selectThemeLinks());
            expires = Instant.now().plusSeconds(300);
        }
        return links;
    }

    private static String normalize(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    public Map<String, Set<String>> themes(Set<String> tickers) {
        var result = new TreeMap<String, Set<String>>();
        for (var link : forTickers(tickers))
            if (link.getTheme() != null && !link.getTheme().isBlank())
                result.computeIfAbsent(link.getTicker(), ignored -> new TreeSet<>())
                        .add(link.getTheme());
        return result;
    }
}
