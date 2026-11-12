package com.app.maria.domain.news.service;

import com.app.maria.domain.news.cache.NewsSummaryCache;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.NewsAnswerContent;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.NewsProvider;
import com.app.maria.domain.news.provider.PreparedNewsResponses;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NewsBriefingService implements NewsBriefingOperations {
    private record ArticleSession(List<Selected> selected, long expiresAtNanos) {}

    private static final long ARTICLE_SESSION_TTL_NANOS = Duration.ofMinutes(3).toNanos();
    private static final int MAX_ARTICLE_SESSIONS = 512;

    private final NewsCustomerContextService contexts;
    private final NewsSelectionService selection;
    private final NewsProvider provider;
    private final NewsAiService ai;
    private final PreparedNewsResponses prepared;
    private final NewsSummaryCache cache;
    private final NewsProperties properties;
    private final BusinessClockService businessClock;
    private final Map<String, ArticleSession> articleSessions =
            new LinkedHashMap<>(16, 0.75f, true);

    private record QuestionWindow(long startedAtNanos, int requests) {}

    private final Map<Long, QuestionWindow> questionWindows = new LinkedHashMap<>(16, 0.75f, true);

    public Briefing briefing(Long customerId) {
        var c = contexts.loadForSelection(customerId);
        var snapshot = provider.snapshot(c);
        var selected = selected(c, snapshot);
        Answer first = null;
        if (!selected.isEmpty()) {
            var a = selected.get(0).article();
            first =
                    cache.peek(summaryKey(c, a))
                            .map(
                                    v ->
                                            answer(
                                                    a,
                                                    v.text(),
                                                    Origin.CACHED_LLM,
                                                    null,
                                                    v.generatedAt(),
                                                    List.of()))
                            .orElse(null);
        }
        return new Briefing(
                customerId,
                c.investorType(),
                c.personalizationStatus(),
                NewsSelectionService.RULE_VERSION,
                selected,
                NewsCacheIdentity.hash(NewsCacheIdentity.context(c) + ":" + snapshot.version()),
                NewsCacheIdentity.summaryVersion(
                        provider.id() + ":" + ai.summaryPromptVersion(), properties.getModel()),
                first,
                provider.themeNames(c));
    }

    private String summaryKey(Context c, NewsArticle a) {
        return NewsCacheIdentity.summary(
                provider.id(),
                a,
                c.investorType(),
                ai.summaryPromptVersion(),
                properties.getModel());
    }

    public String sharedSummaryKey(
            NewsArticle article, com.app.maria.domain.customer.entity.InvestorType type) {
        return NewsCacheIdentity.summary(
                provider.id(), article, type, ai.summaryPromptVersion(), properties.getModel());
    }

    public boolean summaryReady(
            NewsArticle article, com.app.maria.domain.customer.entity.InvestorType type) {
        return (article.demo() && !properties.isLiveSummaries())
                || cache.peek(sharedSummaryKey(article, type)).isPresent();
    }

    public boolean backgroundCapacity() {
        return ai.hasBackgroundCapacity();
    }

    public NewsSummaryCache.Result prepareSummary(
            NewsArticle article, com.app.maria.domain.customer.entity.InvestorType type) {
        if (article.demo() && !properties.isLiveSummaries())
            throw new IllegalStateException("Demo summary is already prepared");
        var publicContext = new Context(0L, java.util.Set.of(), type, PersonalizationStatus.ACTIVE);
        return cache.get(
                sharedSummaryKey(article, type), () -> ai.summarize(article, publicContext));
    }

    public Answer summary(Long customerId, String id, String version) {
        var c = contexts.load(customerId);
        var a = allowed(c, id, version);
        if (a.demo() && !properties.isLiveSummaries())
            return answer(
                    a,
                    prepared.summary(a, c.investorType()),
                    Origin.PREPARED,
                    PreparedReason.DEMO_DEFAULT,
                    null,
                    List.of());
        try {
            var result = cache.get(summaryKey(c, a), () -> ai.summarize(a, c));
            return answer(
                    a,
                    result.value().text(),
                    result.cached() ? Origin.CACHED_LLM : Origin.LIVE_LLM,
                    null,
                    result.value().generatedAt(),
                    List.of());
        } catch (RuntimeException e) {
            log.warn(
                    "News summary generation failed (type={}, reason={})",
                    e.getClass().getSimpleName(),
                    safeReason(e));
            if (!a.demo()) {
                if (e instanceof AppException app) throw app;
                throw new AppException(ErrorType.NEWS_AI_UNAVAILABLE);
            }
            return answer(
                    a,
                    prepared.summary(a, c.investorType()),
                    Origin.PREPARED,
                    PreparedReason.LLM_UNAVAILABLE,
                    null,
                    List.of());
        }
    }

    public Answer question(Long customerId, String id, QuestionRequest request) {
        if (NewsQuestionPolicy.blocked(request.question()))
            return policyRefusal(
                    id,
                    request.articleVersion(),
                    NewsQuestionPolicy.refusalFor(request.question()));
        var c = contexts.load(customerId);
        var a = allowed(c, id, request.articleVersion());
        var canonical = NewsQuestionPolicy.articleQuestionAfterValidation(request.question(), a);
        if (canonical.isEmpty())
            return policyRefusal(
                    id,
                    request.articleVersion(),
                    NewsQuestionPolicy.refusalFor(request.question()));
        // Client history can be forged. Answer only the current question against this article.
        var safeRequest = new QuestionRequest(request.articleVersion(), canonical.get(), List.of());
        // Figure questions always use article evidence, including when live LLM mode is enabled.
        if (NewsAnswerContent.asksForNumbers(safeRequest.question())) {
            return answer(
                    a,
                    prepared.numbers(
                            a,
                            NewsQuestionPolicy.isGeneralNumberQuestion(canonical.get())
                                    ? ""
                                    : request.question()),
                    Origin.PREPARED,
                    PreparedReason.ARTICLE_EVIDENCE,
                    null,
                    List.of());
        }
        if (!properties.isLiveQuestions()) {
            return prepared.answer(a, c.investorType(), safeRequest.question())
                    .map(
                            text ->
                                    answer(
                                            a,
                                            text,
                                            Origin.PREPARED,
                                            PreparedReason.DEMO_DEFAULT,
                                            null,
                                            asksForStocks(safeRequest.question())
                                                    ? related(a, c)
                                                    : List.of()))
                    .orElseGet(
                            () ->
                                    answer(
                                            a,
                                            prepared.articleAnswer(a, safeRequest.question()),
                                            Origin.PREPARED,
                                            PreparedReason.ARTICLE_EVIDENCE,
                                            null,
                                            List.of()));
        }
        try {
            String questionKey = questionKey(a, c, safeRequest);
            var generated =
                    cache.get(
                            questionKey,
                            () -> {
                                consumeQuestionBudget(customerId);
                                return ai.question(a, c, safeRequest);
                            });
            String text = generated.value().text();
            if (NewsQuestionPolicy.containsAdvice(text)) {
                cache.invalidate(questionKey);
                return policyRefusal(a.id(), a.version(), NewsQuestionPolicy.REFUSAL);
            }
            return answer(
                    a,
                    text,
                    generated.cached() ? Origin.CACHED_LLM : Origin.LIVE_LLM,
                    null,
                    generated.value().generatedAt(),
                    asksForStocks(safeRequest.question()) ? related(a, c) : List.of());
        } catch (RuntimeException e) {
            log.warn(
                    "News question generation failed (type={}, reason={})",
                    e.getClass().getSimpleName(),
                    safeReason(e));
            if (e instanceof AppException app
                    && app.getErrorType() == ErrorType.NEWS_QUESTION_RATE_LIMITED) throw app;
            if (!a.demo()) {
                if (e instanceof AppException app) throw app;
                throw new AppException(ErrorType.NEWS_AI_UNAVAILABLE);
            }
            return prepared.answer(a, c.investorType(), safeRequest.question())
                    .map(
                            text ->
                                    answer(
                                            a,
                                            text,
                                            Origin.PREPARED,
                                            PreparedReason.LLM_UNAVAILABLE,
                                            null,
                                            asksForStocks(safeRequest.question())
                                                    ? related(a, c)
                                                    : List.of()))
                    .orElseGet(
                            () ->
                                    answer(
                                            a,
                                            prepared.articleAnswer(a, safeRequest.question()),
                                            Origin.PREPARED,
                                            PreparedReason.ARTICLE_EVIDENCE,
                                            null,
                                            List.of()));
        }
    }

    private String questionKey(NewsArticle article, Context context, QuestionRequest request) {
        return "news-question:"
                + NewsCacheIdentity.hash(
                        provider.id()
                                + "|"
                                + article.id()
                                + "|"
                                + article.version()
                                + "|"
                                + article.title()
                                + "|"
                                + article.body()
                                + "|"
                                + article.facts()
                                + "|"
                                + article.risks()
                                + "|"
                                + article.source()
                                + "|"
                                + article.demo()
                                + "|"
                                + article.relatedStocks()
                                + "|"
                                + context.investorType()
                                + "|"
                                + request.question()
                                + "|"
                                + NewsQuestionPolicy.VERSION
                                + "|"
                                + ai.questionPromptVersion()
                                + "|"
                                + properties.getModel());
    }

    private synchronized void consumeQuestionBudget(Long customerId) {
        long now = System.nanoTime();
        long windowNanos = Duration.ofMinutes(1).toNanos();
        var current = questionWindows.get(customerId);
        if (current == null || now - current.startedAtNanos() >= windowNanos) {
            questionWindows.put(customerId, new QuestionWindow(now, 1));
        } else {
            if (current.requests() >= Math.max(1, properties.getQuestionMaxRequestsPerMinute()))
                throw new AppException(ErrorType.NEWS_QUESTION_RATE_LIMITED);
            questionWindows.put(
                    customerId,
                    new QuestionWindow(current.startedAtNanos(), current.requests() + 1));
        }
        while (questionWindows.size() > 2048)
            questionWindows.remove(questionWindows.keySet().iterator().next());
    }

    private Answer policyRefusal(String id, String version, String message) {
        return new Answer(
                id,
                version,
                new Evidence(List.of(), List.of()),
                message,
                Origin.PREPARED,
                PreparedReason.POLICY_REFUSAL,
                null,
                List.of(),
                null,
                List.of());
    }

    private NewsArticle allowed(Context c, String id, String version) {
        var article =
                selected(c).stream()
                        .map(Selected::article)
                        .filter(a -> a.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new AppException(ErrorType.NEWS_ARTICLE_UNAVAILABLE));
        if (!article.version().equals(version))
            throw new AppException(ErrorType.NEWS_VERSION_CHANGED);
        return article;
    }

    private String selectionKey(Context context, NewsProvider.Snapshot snapshot) {
        return context.customerId()
                + ":"
                + NewsCacheIdentity.portfolio(context)
                + ":"
                + snapshot.version()
                + ":"
                + NewsSelectionService.RULE_VERSION;
    }

    private List<Selected> cachedSelection(String key) {
        long now = System.nanoTime();
        synchronized (articleSessions) {
            var cached = articleSessions.get(key);
            if (cached != null && cached.expiresAtNanos() - now > 0) return cached.selected();
            if (cached != null) articleSessions.remove(key);
        }
        return null;
    }

    private List<Selected> selected(Context context) {
        var snapshot = provider.snapshot(context);
        var cached = cachedSelection(selectionKey(context, snapshot));
        if (cached != null) return cached;
        var full = contexts.loadForSelection(context.customerId());
        if (!NewsCacheIdentity.portfolio(full).equals(NewsCacheIdentity.portfolio(context)))
            throw new AppException(ErrorType.NEWS_ARTICLE_UNAVAILABLE);
        return selected(full, provider.snapshot(full));
    }

    private List<Selected> selected(Context context, NewsProvider.Snapshot snapshot) {
        String key = selectionKey(context, snapshot);
        var cached = cachedSelection(key);
        if (cached != null) return cached;
        var result = selection.select(context, snapshot.articles().get());
        synchronized (articleSessions) {
            long insertedAt = System.nanoTime();
            articleSessions.put(
                    key, new ArticleSession(result, insertedAt + ARTICLE_SESSION_TTL_NANOS));
            Iterator<Map.Entry<String, ArticleSession>> iterator =
                    articleSessions.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().expiresAtNanos() - insertedAt <= 0) {
                    iterator.remove();
                }
            }
            while (articleSessions.size() > MAX_ARTICLE_SESSIONS) {
                iterator = articleSessions.entrySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
        return result;
    }

    private Answer answer(
            NewsArticle a,
            String text,
            Origin origin,
            PreparedReason reason,
            Instant generatedAt,
            List<RelatedStock> stocks) {
        var content = NewsAnswerContent.decode(text, a);
        return new Answer(
                a.id(),
                a.version(),
                new Evidence(a.facts(), a.risks()),
                content.explanation(),
                origin,
                reason,
                generatedAt,
                content.points(),
                content.quote(),
                stocks);
    }

    private List<RelatedStock> related(NewsArticle a, Context c) {
        return a.relatedStocks().stream()
                .map(stock -> new RelatedStock(stock.ticker(), stock.name(), stock.sectorName()))
                .toList();
    }

    private boolean asksForStocks(String question) {
        return question.contains("종목") || question.contains("회사") || question.contains("기업");
    }

    private String safeReason(RuntimeException error) {
        var known =
                java.util.Set.of(
                        "News AI busy",
                        "Invalid news answer",
                        "News answer must be JSON",
                        "Summary must have three points",
                        "Summary contains investment advice",
                        "Answer contains numbers absent from article",
                        "Invalid news JSON",
                        "Invalid news points",
                        "Invalid explanation",
                        "Invalid news input");
        return error.getMessage() != null && known.contains(error.getMessage())
                ? error.getMessage()
                : "upstream_error";
    }
}
