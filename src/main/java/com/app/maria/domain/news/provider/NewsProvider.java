package com.app.maria.domain.news.provider;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.Context;
import java.util.List;

/** Implementations must have permission to pass the full article to AI. */
public interface NewsProvider {
    String id();

    List<NewsArticle> articles();

    default java.util.Optional<List<NewsArticle>> cachedArticles(Context context) {
        return java.util.Optional.empty();
    }

    default void configureInventory(Context context) {}

    default String contextVersion(Context context) {
        return id();
    }

    default java.util.List<String> themeNames(Context context) {
        return java.util.List.of();
    }

    default List<NewsArticle> articles(Context context) {
        return articles();
    }
}
