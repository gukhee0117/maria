package com.app.maria.domain.news.dto;

import java.time.Instant;

/** Immutable bounds captured by the provider before collection. */
public record NewsSearchWindow(Instant from, Instant to, String cacheKey) {}
