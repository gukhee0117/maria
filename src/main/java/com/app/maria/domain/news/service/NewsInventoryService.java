package com.app.maria.domain.news.service;

import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.mapper.NewsInventoryMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewsInventoryService {
    public record Group(Map<String, String> stockNames, long customers) {
        public Group {
            stockNames = Map.copyOf(stockNames);
        }
    }

    public record Inventory(Map<String, String> stockNames, List<Group> groups) {
        public Inventory {
            stockNames = Map.copyOf(stockNames);
            groups = List.copyOf(groups);
        }
    }

    private final NewsInventoryMapper mapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private Inventory cached;
    private Instant expiresAt = Instant.MIN;

    @Autowired
    public NewsInventoryService(NewsInventoryMapper mapper, NewsProperties properties) {
        this(mapper, Clock.systemUTC(), properties.getInventoryCacheTtl());
    }

    @Transactional(readOnly = true)
    public synchronized Inventory load() {
        if (cached != null && expiresAt.isAfter(clock.instant())) return cached;
        Map<Map<String, String>, Long> groups = new HashMap<>();
        Map<String, String> stocks = new TreeMap<>();
        Map<String, String> current = new TreeMap<>();
        var last = new java.util.concurrent.atomic.AtomicReference<Long>();
        mapper.scanHoldings(
                result -> {
                    var row = result.getResultObject();
                    if (last.get() != null && !last.get().equals(row.getCustomerId())) {
                        groups.merge(Map.copyOf(current), 1L, Long::sum);
                        current.clear();
                    }
                    last.set(row.getCustomerId());
                    current.putIfAbsent(row.getTicker(), row.getName());
                    stocks.putIfAbsent(row.getTicker(), row.getName());
                });
        if (!current.isEmpty()) groups.merge(Map.copyOf(current), 1L, Long::sum);
        var list =
                groups.entrySet().stream()
                        .map(e -> new Group(e.getKey(), e.getValue()))
                        .sorted(
                                Comparator.comparingLong(Group::customers)
                                        .reversed()
                                        .thenComparing(
                                                g -> new TreeMap<>(g.stockNames()).toString()))
                        .toList();
        cached = new Inventory(stocks, list);
        expiresAt = clock.instant().plus(cacheTtl);
        return cached;
    }

    NewsInventoryService(NewsInventoryMapper mapper) {
        this(mapper, Clock.systemUTC(), Duration.ofMinutes(30));
    }

    NewsInventoryService(NewsInventoryMapper mapper, Clock clock, Duration cacheTtl) {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative())
            throw new IllegalArgumentException("Invalid inventory cache TTL");
        this.mapper = mapper;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }
}
