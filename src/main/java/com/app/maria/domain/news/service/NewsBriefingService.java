package com.app.maria.domain.news.service;

import com.app.maria.domain.news.cache.NewsSummaryCache;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.NewsAnswerContent;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.NewsProvider;
import com.app.maria.domain.news.provider.PreparedNewsResponses;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NewsBriefingService {
    private final NewsCustomerContextService contexts;
    private final NewsSelectionService selection;
    private final NewsProvider provider;
    private final NewsAiService ai;
    private final PreparedNewsResponses prepared;
    private final NewsSummaryCache cache;
    private final NewsProperties properties;

    public Briefing briefing(Long customerId) {
        var c = contexts.load(customerId);
        var selected = selection.select(c, provider.articles(c));
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
                                                    related(a, c)))
                            .orElse(null);
        }
        return new Briefing(
                customerId,
                c.investorType(),
                c.personalizationStatus(),
                NewsSelectionService.RULE_VERSION,
                selected,
                NewsCacheIdentity.context(c),
                NewsCacheIdentity.hash(
                        provider.id()
                                + ":"
                                + ai.summaryPromptVersion()
                                + ":"
                                + properties.getModel()),
                first);
    }

    private String summaryKey(Context c, NewsArticle a) {
        return provider.id()
                + ":"
                + a.id()
                + ":"
                + a.version()
                + ":"
                + NewsCacheIdentity.context(c)
                + ":"
                + ai.summaryPromptVersion()
                + ":"
                + properties.getModel();
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
                    related(a, c));
        try {
            var result = cache.get(summaryKey(c, a), () -> ai.summarize(a, c));
            return answer(
                    a,
                    result.value().text(),
                    result.cached() ? Origin.CACHED_LLM : Origin.LIVE_LLM,
                    null,
                    result.value().generatedAt(),
                    related(a, c));
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
                    related(a, c));
        }
    }

    public Answer question(Long customerId, String id, QuestionRequest request) {
        var canonical = NewsQuestionPolicy.canonicalQuestion(request.question());
        if (canonical.isEmpty()) return policyRefusal(id, request.articleVersion());
        var c = contexts.load(customerId);
        var a = allowed(c, id, request.articleVersion());
        // Client history can be forged. Reviewed article intents are self-contained.
        var safeRequest = new QuestionRequest(request.articleVersion(), canonical.get(), List.of());
        try {
            var generated = ai.question(a, c, safeRequest);
            if (NewsQuestionPolicy.containsAdvice(generated))
                return policyRefusal(a.id(), a.version());
            return answer(
                    a,
                    generated,
                    Origin.LIVE_LLM,
                    null,
                    Instant.now(),
                    asksForStocks(safeRequest.question()) ? related(a, c) : List.of());
        } catch (RuntimeException e) {
            log.warn(
                    "News question generation failed (type={}, reason={})",
                    e.getClass().getSimpleName(),
                    safeReason(e));
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
                    .orElseThrow(() -> new AppException(ErrorType.NEWS_AI_UNAVAILABLE));
        }
    }

    private Answer policyRefusal(String id, String version) {
        return new Answer(
                id,
                version,
                new Evidence(List.of(), List.of()),
                NewsQuestionPolicy.REFUSAL,
                Origin.PREPARED,
                PreparedReason.POLICY_REFUSAL,
                null,
                List.of(),
                null,
                List.of());
    }

    private NewsArticle allowed(Context c, String id, String version) {
        var article =
                selection.select(c, provider.articles(c)).stream()
                        .map(Selected::article)
                        .filter(a -> a.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new AppException(ErrorType.NEWS_ARTICLE_UNAVAILABLE));
        if (!article.version().equals(version))
            throw new AppException(ErrorType.NEWS_VERSION_CHANGED);
        return article;
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
        return a.tickers().stream()
                .sorted()
                .map(
                        t ->
                                new RelatedStock(
                                        t,
                                        c.stockNames().getOrDefault(t, t),
                                        c.stockSectors().get(t)))
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
