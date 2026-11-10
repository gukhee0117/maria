package com.app.maria.domain.news.provider;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.Context;
import java.util.List;

/** Implementations must have permission to pass the full article to AI. */
public interface NewsProvider {
    record Snapshot(
            String version,
            java.util.function.Supplier<List<NewsArticle>> articles,
            java.util.function.Supplier<java.util.Optional<List<NewsArticle>>> cachedArticles) {}

    default Snapshot snapshot(Context context) {
        return new Snapshot(id(), () -> articles(context), () -> cachedArticles(context));
    }

    String id();

    List<NewsArticle> articles();

    default java.util.Optional<List<NewsArticle>> cachedArticles(Context context) {
        return java.util.Optional.empty();
    }

    default void configureInventory(Context context) {}

    default java.util.List<String> themeNames(Context context) {
        return java.util.List.of();
    }

    default List<NewsArticle> articles(Context context) {
        return articles();
    }
}
