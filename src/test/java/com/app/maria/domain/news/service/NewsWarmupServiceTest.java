package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.news.cache.NewsSummaryCache;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.NewsProvider;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsWarmupServiceTest {
    @Test
    void allDynamicGroupsShareFirstTargetsAndPlanningNeverFetchesPerCustomer() {
        var inventory = mock(NewsInventoryService.class);
        var provider = mock(NewsProvider.class, CALLS_REAL_METHODS);
        var service = mock(NewsBriefingService.class);
        var properties = new NewsProperties();
        var ready = new HashSet<String>();
        when(inventory.load())
                .thenReturn(
                        new NewsInventoryService.Inventory(
                                Map.of("005930", "????"),
                                List.of(
                                        new NewsInventoryService.Group(
                                                Map.of("005930", "????"), 48000),
                                        new NewsInventoryService.Group(
                                                Map.of("005930", "????"), 1))));
        when(provider.cachedArticles(any()))
                .thenReturn(Optional.of(List.of(NewsSelectionServiceTest.article("a", 5, 5))));
        when(service.backgroundCapacity()).thenReturn(true);
        when(service.sharedSummaryKey(any(), any())).thenAnswer(i -> "a:" + i.getArgument(1));
        when(service.summaryReady(any(), any()))
                .thenAnswer(i -> ready.contains("a:" + i.getArgument(1)));
        when(service.prepareSummary(any(), any()))
                .thenAnswer(
                        i -> {
                            ready.add("a:" + i.getArgument(1));
                            return new NewsSummaryCache.Result(
                                    new NewsSummaryCache.Value("text", Instant.now()), false);
                        });
        var warmup =
                new NewsWarmupService(
                        inventory, provider, new NewsSelectionService(), service, properties);
        try {
            var first = warmup.runCycle();
            assertThat(first.generated()).isEqualTo(5);
            assertThat(first.targets()).isEqualTo(5);
            var second = warmup.runCycle();
            assertThat(second.generated()).isZero();
            assertThat(second.reused()).isEqualTo(5);
            verify(service, times(5)).prepareSummary(any(), any());
            verify(provider, never()).articles(any());
        } finally {
            warmup.close();
        }
    }

    @Test
    void failedKeysBackOffAndDoNotPreventOtherProfilesOrRealtimeFallback() {
        var inventory = mock(NewsInventoryService.class);
        var provider = mock(NewsProvider.class, CALLS_REAL_METHODS);
        var service = mock(NewsBriefingService.class);
        var properties = new NewsProperties();
        when(inventory.load())
                .thenReturn(
                        new NewsInventoryService.Inventory(
                                Map.of("999999", "???"),
                                List.of(
                                        new NewsInventoryService.Group(
                                                Map.of("999999", "???"), 1))));
        var a = NewsSelectionServiceTest.article("a", 5, 5);
        var mapped =
                new com.app.maria.domain.news.dto.NewsArticle(
                        a.id(),
                        a.version(),
                        a.title(),
                        a.body(),
                        a.source(),
                        a.url(),
                        a.publishedAt(),
                        List.of("999999"),
                        a.facts(),
                        a.risks(),
                        5,
                        5,
                        5);
        when(provider.cachedArticles(any())).thenReturn(Optional.of(List.of(mapped)));
        when(service.backgroundCapacity()).thenReturn(true);
        when(service.sharedSummaryKey(any(), any())).thenAnswer(i -> "a:" + i.getArgument(1));
        when(service.prepareSummary(any(), any())).thenThrow(new IllegalStateException("offline"));
        var warmup =
                new NewsWarmupService(
                        inventory, provider, new NewsSelectionService(), service, properties);
        try {
            assertThat(warmup.runCycle().failed()).isEqualTo(5);
            assertThat(warmup.runCycle().failed()).isZero();
            verify(service, times(5)).prepareSummary(any(), any());
            properties.setPrewarmEnabled(false);
            warmup.runCycle();
            verify(inventory, times(2)).load();
        } finally {
            warmup.close();
        }
    }
}
