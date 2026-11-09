package com.app.maria.domain.news.dto;

import com.app.maria.domain.customer.entity.InvestorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Complete customer-independent input shared by generation and its cache key. */
public record NewsSummaryInput(Article article, InvestorType investorType) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Article(
            String title,
            String body,
            List<String> facts,
            List<String> risks,
            String source,
            boolean demo) {}

    public static NewsSummaryInput of(NewsArticle article, InvestorType type) {
        return new NewsSummaryInput(
                new Article(
                        article.title(),
                        article.body(),
                        article.facts(),
                        article.risks(),
                        article.source(),
                        article.demo()),
                type);
    }

    public String json() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid news input", e);
        }
    }
}
