package com.app.maria.domain.news.service;

import com.app.maria.domain.news.dto.NewsAnswerContent;
import com.app.maria.domain.news.dto.NewsArticle;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class NewsAiService {
    public static final String PROMPT_VERSION = "news-v3-points-numbers-quotes";
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final String summaryPrompt;
    private final String questionPrompt;
    private final Semaphore slots = new Semaphore(4);

    public NewsAiService(@Qualifier("newsChatClient") ChatClient client, ObjectMapper mapper)
            throws IOException {
        this.client = client;
        this.mapper = mapper;
        summaryPrompt = read("summary-prompt.txt");
        questionPrompt = read("question-prompt.txt");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource("news/" + path).getContentAsString(StandardCharsets.UTF_8);
    }

    public String summarize(NewsArticle a, Context c) {
        return generate(a, c, null);
    }

    public String summaryPromptVersion() {
        return PROMPT_VERSION + ":" + NewsCacheIdentity.hash(summaryPrompt);
    }

    public String question(NewsArticle a, Context c, QuestionRequest request) {
        return generate(a, c, request);
    }

    private String generate(NewsArticle article, Context context, QuestionRequest request) {
        if (!slots.tryAcquire()) throw new IllegalStateException("News AI busy");
        try {
            // No financial data, tools, advisors, provider search results or shared history.
            String input =
                    mapper.writeValueAsString(
                            Map.of(
                                    "article",
                                    Map.of(
                                            "title",
                                            article.title(),
                                            "body",
                                            article.body(),
                                            "facts",
                                            article.facts(),
                                            "risks",
                                            article.risks(),
                                            "source",
                                            article.source(),
                                            "demo",
                                            article.demo()),
                                    "investorType",
                                    context.investorType(),
                                    "question",
                                    request == null ? "기사의 핵심을 설명해 주세요." : request.question(),
                                    "history",
                                    request == null ? java.util.List.of() : request.messages(),
                                    "numericEvidence",
                                    request != null
                                                    && NewsAnswerContent.asksForNumbers(
                                                            request.question())
                                            ? NewsAnswerContent.numericEvidence(article)
                                            : java.util.List.of(),
                                    "knownStocks",
                                    request != null
                                                    && NewsAnswerContent.asksForNumbers(
                                                            request.question())
                                            ? java.util.List.of()
                                            : article.tickers().stream()
                                                    .sorted()
                                                    .map(
                                                            t ->
                                                                    Map.of(
                                                                            "ticker",
                                                                            t,
                                                                            "name",
                                                                            context.stockNames()
                                                                                    .getOrDefault(
                                                                                            t, t)))
                                                    .toList()));
            String text =
                    client
                            .prompt()
                            .system(request == null ? summaryPrompt : questionPrompt)
                            .user(input)
                            .stream()
                            .content()
                            .collectList()
                            .map(parts -> String.join("", parts))
                            .timeout(Duration.ofSeconds(20))
                            .block();
            if (text == null || text.isBlank() || text.length() > 2000)
                throw new IllegalStateException("Invalid news answer");
            text =
                    text.strip()
                            .replaceFirst("(?i)^```(?:json)?\\s*", "")
                            .replaceFirst("\\s*```$", "");
            if (!text.startsWith("{")) throw new IllegalStateException("News answer must be JSON");
            var content = NewsAnswerContent.decode(text, article);
            if (request == null && content.points().size() != 3)
                throw new IllegalStateException("Summary must have three points");
            if (request != null
                    && NewsAnswerContent.asksForNumbers(request.question())
                    && !content.numbersComeFrom(article))
                throw new IllegalStateException("Answer contains numbers absent from article");
            if (request != null && NewsAnswerContent.asksForNumbers(request.question()))
                content = content.withNumericSourceQuote(article);
            return mapper.writeValueAsString(content);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw new AppException(ErrorType.NEWS_AI_AUTH_FAILED);
            throw e;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid news input", e);
        } finally {
            slots.release();
        }
    }
}
