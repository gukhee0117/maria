package com.app.maria.domain.news.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

/** Only licensed or self-authored full-text articles enter the AI path. */
public record NewsArticle(
        String id,
        String version,
        String title,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String body,
        String source,
        String url,
        LocalDateTime publishedAt,
        List<String> tickers,
        List<String> facts,
        List<String> risks,
        int importance,
        int stability,
        int growth,
        boolean demo,
        List<NewsStockReference> relatedStocks,
        // In-memory selection metadata. JSON persistence must restore matched themes before
        // selection.
        @JsonIgnore List<String> selectionThemes) {
    public NewsArticle(
            String id,
            String version,
            String title,
            String body,
            String source,
            String url,
            LocalDateTime publishedAt,
            List<String> tickers,
            List<String> facts,
            List<String> risks,
            int importance,
            int stability,
            int growth,
            boolean demo,
            List<NewsStockReference> relatedStocks) {
        this(
                id,
                version,
                title,
                body,
                source,
                url,
                publishedAt,
                tickers,
                facts,
                risks,
                importance,
                stability,
                growth,
                demo,
                relatedStocks,
                List.of());
    }

    public NewsArticle(
            String id,
            String version,
            String title,
            String body,
            String source,
            String url,
            LocalDateTime publishedAt,
            List<String> tickers,
            List<String> facts,
            List<String> risks,
            int importance,
            int stability,
            int growth,
            boolean demo) {
        this(
                id,
                version,
                title,
                body,
                source,
                url,
                publishedAt,
                tickers,
                facts,
                risks,
                importance,
                stability,
                growth,
                demo,
                List.of());
    }

    public NewsArticle(
            String id,
            String version,
            String title,
            String body,
            String source,
            String url,
            LocalDateTime publishedAt,
            List<String> tickers,
            List<String> facts,
            List<String> risks,
            int importance,
            int stability,
            int growth) {
        this(
                id,
                version,
                title,
                body,
                source,
                url,
                publishedAt,
                tickers,
                facts,
                risks,
                importance,
                stability,
                growth,
                true,
                List.of());
    }

    public NewsArticle {
        tickers = List.copyOf(tickers);
        facts = List.copyOf(facts);
        risks = List.copyOf(risks);
        relatedStocks = relatedStocks == null ? List.of() : List.copyOf(relatedStocks);
        selectionThemes = selectionThemes == null ? List.of() : List.copyOf(selectionThemes);
    }
}
