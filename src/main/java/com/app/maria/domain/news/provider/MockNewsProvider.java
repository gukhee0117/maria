package com.app.maria.domain.news.provider;

import com.app.maria.domain.news.dto.NewsArticle;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "news.provider", havingValue = "demo")
public class MockNewsProvider implements NewsProvider {
    private final List<NewsArticle> articles;

    public MockNewsProvider(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("news/demo-articles.json").getInputStream()) {
            articles =
                    List.copyOf(mapper.readValue(input, new TypeReference<List<NewsArticle>>() {}));
        }
        var ids = new HashSet<String>();
        for (var a : articles) {
            if (!ids.add(a.id())
                    || a.version().isBlank()
                    || a.body().isBlank()
                    || a.facts().isEmpty()
                    || a.risks().isEmpty()
                    || a.tickers().isEmpty()
                    || a.tickers().stream().anyMatch(t -> !t.matches("[0-9]{6}"))
                    || a.importance() < 0
                    || a.importance() > 10
                    || a.stability() < 0
                    || a.stability() > 10
                    || a.growth() < 0
                    || a.growth() > 10
                    || !a.facts().stream().allMatch(a.body()::contains)
                    || !a.risks().stream().allMatch(a.body()::contains)) {
                throw new IllegalStateException("Invalid self-authored news fixture: " + a.id());
            }
        }
    }

    public String id() {
        return "MARIA_DEMO_V1";
    }

    public List<NewsArticle> articles() {
        return articles;
    }

    @Override
    public java.util.Optional<List<NewsArticle>> cachedArticles(
            com.app.maria.domain.news.dto.NewsContract.Context context) {
        return java.util.Optional.of(articles);
    }
}
