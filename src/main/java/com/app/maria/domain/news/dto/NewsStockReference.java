package com.app.maria.domain.news.dto;

/** A domestic stock explicitly mentioned in the news article text. */
public record NewsStockReference(String ticker, String name, String sectorName) {}
