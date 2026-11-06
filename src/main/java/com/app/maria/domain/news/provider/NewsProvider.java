package com.app.maria.domain.news.provider;

import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.Context;
import java.util.List;

/** Implementations must have permission to pass the full article to AI. */
public interface NewsProvider {
    String id();

    List<NewsArticle> articles();

    default List<NewsArticle> articles(Context context) {
        return articles();
    }
}
