package com.app.maria.domain.news.service;

import com.app.maria.domain.news.dto.NewsContract.Context;
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

    public static String context(Context context) {
        return hash(
                context.investorType()
                        + "|"
                        + context.personalizationStatus()
                        + "|"
                        + context.tickers().stream().sorted().toList()
                        + "|"
                        + new TreeMap<>(context.stockNames())
                        + "|"
                        + new TreeMap<>(context.stockSectors()));
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
