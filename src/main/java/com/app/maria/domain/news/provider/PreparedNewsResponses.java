package com.app.maria.domain.news.provider;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.NewsAnswerContent;
import com.app.maria.domain.news.dto.NewsArticle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Human-authored demo explanations, never represented as generated LLM output. */
@Component
public class PreparedNewsResponses {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String summary(NewsArticle article, InvestorType type) {
        String focus =
                switch (type) {
                    case STABLE, CONSERVATIVE -> "안정성 관점에서는 위험 요인을 먼저 확인해 주세요. ";
                    case ACTIVE, AGGRESSIVE -> "성장 관점에서는 사업 변화와 함께 위험 요인도 확인해 주세요. ";
                    default -> "사업 변화와 위험 요인을 함께 살펴보세요. ";
                };
        var points = new java.util.ArrayList<String>();
        points.add(focus + article.facts().get(0));
        points.add(article.facts().size() > 1 ? article.facts().get(1) : article.facts().get(0));
        points.add(article.risks().get(0));
        return MAPPER.valueToTree(new NewsAnswerContent(String.join(" ", points), points, null))
                .toString();
    }

    public Optional<String> answer(NewsArticle article, InvestorType type, String question) {
        return switch (question.trim()) {
            case "이 기사를 쉽게 설명해 줘" -> Optional.of(summary(article, type));
            case "주의할 위험은 뭐야?" -> Optional.of(String.join(" ", article.risks()));
            case "관련 종목이 뭐야?" -> Optional.of("기사에서 확인된 관련 종목은 아래에서 확인할 수 있어요.");
            default -> Optional.empty();
        };
    }

    // Called by NewsBriefingService in the service package; this API crosses package boundaries.
    public String numbers(NewsArticle article) {
        var points = NewsAnswerContent.numericEvidence(article).stream().limit(5).toList();
        var explanation =
                points.isEmpty() ? "이 기사에는 정리할 수 있는 수치 정보가 없어요." : String.join(" ", points);
        var quote =
                points.stream()
                        .filter(s -> s.length() <= 200 && article.body().contains(s))
                        .findFirst()
                        .orElse(null);
        return MAPPER.valueToTree(
                        new NewsAnswerContent(explanation, points, quote)
                                .withNumericSourceQuote(article))
                .toString();
    }
}
