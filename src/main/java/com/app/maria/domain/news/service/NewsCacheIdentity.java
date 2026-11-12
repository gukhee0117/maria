package com.app.maria.domain.news.service;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.config.NewsAiConfig;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.Context;
import com.app.maria.domain.news.dto.NewsSummaryInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Fingerprints every portfolio-dependent summary input without exposing holdings in cache metadata.
 */
public final class NewsCacheIdentity {
    private NewsCacheIdentity() {}

    public static String summaryVersion(String promptVersion, String model) {
        return hash(
                promptVersion
                        + ":"
                        + model
                        + ":"
                        + NewsAiService.SUMMARY_VALIDATION_VERSION
                        + ":temperature=0:maxTokens="
                        + NewsAiConfig.SUMMARY_MAX_TOKENS);
    }

    public static String summary(
            String provider,
            NewsArticle article,
            InvestorType type,
            String promptVersion,
            String model) {
        return provider
                + ":"
                + article.id()
                + ":"
                + article.version()
                + ":"
                + hash(NewsSummaryInput.of(article, type).json())
                + ":"
                + summaryVersion(promptVersion, model);
    }

    public static String context(Context context) {
        return hash(portfolio(context) + "|" + new TreeMap<>(context.themePriorities()));
    }

    public static String portfolio(Context context) {
        return hash(
                context.investorType()
                        + "|"
                        + context.personalizationStatus()
                        + "|"
                        + context.tickers().stream().sorted().toList()
                        + "|"
                        + new TreeMap<>(context.stockNames())
                        + "|"
                        + new TreeMap<>(context.stockSectors())
                        + "|"
                        + context.orderRevision()
                        + "|"
                        + context.balanceRevision());
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
