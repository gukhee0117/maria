package com.app.maria.domain.news.provider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.client.FreeNewsClient;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FreeNewsProviderTest {
    ObjectMapper mapper = new ObjectMapper();
    FreeNewsClient client = mock(FreeNewsClient.class);
    NewsSearchWindowResolver windows = mock(NewsSearchWindowResolver.class);

    FreeNewsProvider provider(FreeNewsClient source) {
        return new FreeNewsProvider(
                source,
                Clock.systemUTC(),
                new com.app.maria.domain.news.config.NewsProperties(),
                windows);
    }

    Context context =
            new Context(
                    1L,
                    Set.of("005930"),
                    InvestorType.STABLE,
                    PersonalizationStatus.ACTIVE,
                    Map.of("005930", "삼성전자"));
    String body =
            "삼성전자가 신규 반도체 사업 계획을 발표했다. "
                    + "새로운 제품의 공급 확대와 매출 증가를 기대하고 있다고 설명했다. ".repeat(4)
                    + "다만 수요 둔화와 비용 증가 위험이 남아 있다.";

    ObjectNode article(String title, String url, String text) {
        var node = mapper.createObjectNode();
        node.put("title", title);
        node.put("url", url);
        node.put("text", text);
        node.put("lang", "ko");
        node.put("country", "KR");
        node.put("published_at", "2026-11-05T01:00:00Z");
        node.put("sitename", "테스트 언론사");
        return node;
    }

    @org.junit.jupiter.api.BeforeEach
    void defaultWindow() {
        when(windows.capture())
                .thenReturn(
                        NewsSearchWindowResolver.window(
                                LocalDate.of(2026, 11, 5), Instant.parse("2026-11-10T03:00:00Z")));
    }

    @Test
    void snapshotVersionAndReadsStayOnCapturedDateWithoutMoreClockQueries() {
        when(client.search(any(), any())).thenReturn(mapper.createArrayNode());
        var provider = provider(client);
        try {
            var snapshot = provider.snapshot(context);
            clearInvocations(windows);
            when(windows.capture())
                    .thenThrow(new IllegalStateException("must not query clock again"));
            assertThat(snapshot.version()).isEqualTo("2026-11-05:closed");
            assertThat(snapshot.version()).isEqualTo("2026-11-05:closed");
            assertThat(snapshot.articles().get()).isEmpty();
            assertThat(snapshot.cachedArticles().get()).isPresent();
            verifyNoInteractions(windows);
        } finally {
            provider.close();
        }
    }

    @Test
    void capturesOneWindowForAllTickersAndCacheReadsKeepTheTtl() {
        var captured =
                NewsSearchWindowResolver.window(
                        LocalDate.of(2026, 11, 18), Instant.parse("2026-11-10T03:00:00Z"));
        when(windows.capture()).thenReturn(captured);
        clearInvocations(client, windows);
        when(client.search(any(), any())).thenReturn(mapper.createArrayNode());
        var pair =
                new Context(
                        1L,
                        Set.of("005930", "000660"),
                        InvestorType.STABLE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("005930", "삼성전자", "000660", "SK하이닉스"));
        var provider = provider(client);
        try {
            provider.articles(pair);
            verify(windows, times(1)).capture();
            verify(client, times(2)).search(any(), same(captured));
            clearInvocations(client, windows);
            assertThat(provider.cachedArticles(pair)).isPresent();
            verify(windows, times(1)).capture();
            verify(client, never()).search(any(), any());
        } finally {
            provider.close();
        }
    }

    @Test
    void businessDateChangeDoesNotReuseCachedNews() {

        when(client.search(any(), any())).thenReturn(mapper.createArrayNode());
        var provider = provider(client);
        try {
            provider.articles(context);
            assertThat(provider.cachedArticles(context)).isPresent();

            when(windows.capture())
                    .thenReturn(
                            NewsSearchWindowResolver.window(
                                    LocalDate.of(2026, 11, 6),
                                    Instant.parse("2026-11-10T03:00:00Z")));
            assertThat(provider.cachedArticles(context)).isEmpty();

            provider.articles(context);
            verify(client, times(2)).search(eq("삼성전자"), any());
        } finally {
            provider.close();
        }
    }

    @Test
    void titleAndSourceChangesInvalidateVersionWithoutChangingBodyDeduplication() {
        var original = article("삼성전자 사업 확대", "https://example.com/news", body);
        var firstClient = mock(FreeNewsClient.class);
        var changedClient = mock(FreeNewsClient.class);
        when(firstClient.search(any(), any())).thenReturn(mapper.createArrayNode().add(original));
        var changed = original.deepCopy();
        changed.put("sitename", "변경 언론사");
        changed.put("title", "삼성전자 사업 확대 후속 발표");
        when(changedClient.search(any(), any()))
                .thenReturn(
                        mapper.createArrayNode()
                                .add(changed)
                                .add(
                                        article(
                                                "삼성전자 같은 본문 재전송",
                                                "https://example.com/duplicate",
                                                body)));
        var first = provider(firstClient);
        var next = provider(changedClient);
        try {
            var before = first.articles(context).get(0);
            var after = next.articles(context);
            assertThat(after).hasSize(1);
            assertThat(after.get(0).id()).isEqualTo(before.id());
            assertThat(after.get(0).version()).isNotEqualTo(before.version());
        } finally {
            first.close();
            next.close();
        }
    }

    @Test
    void filtersMissingBodyUnrelatedAndDuplicateArticlesAndCachesByStock() {
        var valid = article("삼성전자 사업 확대", "https://example.com/news?id=1", body);
        var tracking = article("삼성전자 사업 확대", "https://example.com/news?id=1&utm_source=feed", body);
        var missing = article("삼성전자 실적", "https://example.com/empty", "");
        var unrelated = article("기업인 춤 영상 인기", "https://example.com/unrelated", body);
        var english = article("삼성전자", "https://example.com/en", body);
        english.put("lang", "en");
        when(client.search(eq("삼성전자"), any()))
                .thenReturn(
                        mapper.createArrayNode()
                                .add(valid)
                                .add(tracking)
                                .add(missing)
                                .add(unrelated)
                                .add(english));
        var provider = provider(client);
        var first = provider.articles(context);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).demo()).isFalse();
        assertThat(first.get(0).body()).isEqualTo(body);
        assertThat(first.get(0).tickers()).containsExactly("005930");
        assertThat(first.get(0).publishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 10, 0));
        assertThat(
                        provider.articles(
                                new Context(
                                        2L,
                                        context.tickers(),
                                        InvestorType.AGGRESSIVE,
                                        PersonalizationStatus.ACTIVE,
                                        context.stockNames())))
                .isEqualTo(first);
        verify(client, times(1)).search(eq("삼성전자"), any());
    }

    @Test
    void financialTitleAndBodyReferenceAreRelevantEvenWithoutCompanyInTitle() {
        when(client.search(eq("삼성전자"), any()))
                .thenReturn(
                        mapper.createArrayNode()
                                .add(article("반도체 투톱 실적 증가", "https://example.com/news", body)));
        assertThat(provider(client).articles(context)).hasSize(1);
    }

    @Test
    void noHoldingsDoesNotCallApiAndProviderFailureIsNotEmptyNewsOrCached() {
        var provider = provider(client);
        assertThat(
                        provider.articles(
                                new Context(
                                        1L,
                                        Set.of(),
                                        InvestorType.STABLE,
                                        PersonalizationStatus.ACTIVE)))
                .isEmpty();
        verifyNoInteractions(client);
        when(client.search(any(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> provider.articles(context))
                .isInstanceOf(IllegalStateException.class);
        doReturn(
                        mapper.createArrayNode()
                                .add(article("삼성전자 실적 증가", "https://example.com/news", body)))
                .when(client)
                .search(any(), any());
        assertThat(provider.articles(context)).hasSize(1);
    }

    @Test
    void coldStockQueriesRunAtMostTwoAtOnceAndKeepDeterministicOutputOrder() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        when(client.search(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String name = invocation.getArgument(0);
                            int n = active.incrementAndGet();
                            peak.accumulateAndGet(n, Math::max);
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IllegalStateException("test release timed out");
                                return mapper.createArrayNode()
                                        .add(
                                                article(
                                                        name + " 실적 증가",
                                                        "https://example.com/" + name,
                                                        body.replace("삼성전자", name)));
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        var provider = provider(client);
        var caller = Executors.newSingleThreadExecutor();
        var pair =
                new Context(
                        1L,
                        Set.of("005930", "000660"),
                        InvestorType.STABLE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("005930", "삼성전자", "000660", "SK하이닉스"));
        try {
            var result = caller.submit(() -> provider.articles(pair));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS))
                    .extracting(a -> a.tickers().get(0))
                    .containsExactly("000660", "005930");
            assertThat(peak.get()).isEqualTo(2);
        } finally {
            release.countDown();
            provider.close();
            caller.shutdownNow();
        }
    }

    @Test
    void cachedStockDoesNotWaitBehindOtherBlockedNetworkQueries() throws Exception {
        when(client.search(eq("삼성전자"), any()))
                .thenReturn(
                        mapper.createArrayNode()
                                .add(article("삼성전자 실적", "https://example.com/samsung", body)));
        var provider = provider(client);
        provider.articles(context);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        for (String name : List.of("SK하이닉스", "NAVER"))
            when(client.search(eq(name), any()))
                    .thenAnswer(
                            invocation -> {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IllegalStateException();
                                return mapper.createArrayNode();
                            });
        var callers = Executors.newFixedThreadPool(2);
        var cold =
                new Context(
                        2L,
                        Set.of("000660", "035420"),
                        InvestorType.ACTIVE,
                        PersonalizationStatus.ACTIVE,
                        Map.of("000660", "SK하이닉스", "035420", "NAVER"));
        try {
            var pending = callers.submit(() -> provider.articles(cold));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(callers.submit(() -> provider.articles(context)).get(1, TimeUnit.SECONDS))
                    .hasSize(1);
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            verify(client, times(1)).search(eq("삼성전자"), any());
        } finally {
            release.countDown();
            provider.close();
            callers.shutdownNow();
        }
    }

    @Test
    void fullArticleBodyIsNotSerializedIntoCustomerApi() throws Exception {
        var provider = new MockNewsProvider(new ObjectMapper().findAndRegisterModules());
        var article = provider.articles().get(0);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(article);
        assertThat(json.has("body")).isFalse();
        assertThat(json.path("demo").asBoolean()).isTrue();
        assertThat(article.body()).isNotBlank();
    }
}
