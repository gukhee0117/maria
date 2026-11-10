package com.app.maria.domain.news.dto;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsAnswerContentTest {
    NewsArticle article =
            new NewsArticle(
                    "a",
                    "v1",
                    "반도체 사업 계획",
                    "내년 HBM 생산능력을 올해의 2배로 확대할 계획이다. 매출은 1,000억원이며 전년보다 12.5% 증가했다. 공급 계약은 확정되지 않았다.",
                    "테스트",
                    null,
                    LocalDateTime.of(2026, 11, 5, 9, 0),
                    List.of("005930"),
                    List.of(),
                    List.of(),
                    5,
                    5,
                    5);

    @Test
    void equalRankedFiguresStayInSourceOrderAndDuplicatesAreExcluded() {
        String body =
                "매출 1억원이다. "
                        + java.util.stream.IntStream.rangeClosed(1, 80)
                                .mapToObj(i -> "매출 " + i + "억원이다.")
                                .collect(java.util.stream.Collectors.joining(" "));
        var source =
                new NewsArticle(
                        "order",
                        "v1",
                        "매출 공시",
                        body,
                        "테스트",
                        null,
                        LocalDateTime.of(2026, 11, 9, 9, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        5,
                        5,
                        5);
        assertThat(NewsAnswerContent.numericEvidence(source))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 12)
                                .mapToObj(i -> "매출 " + i + "억원이다.")
                                .toList());
    }

    @Test
    void numericResultKeepsMatchingAndFallbackMetadataTogether() {
        var matching = NewsAnswerContent.numericEvidenceResult(article, "매출은 얼마야?");
        assertThat(matching.matchesQuestion()).isTrue();
        assertThat(matching.points()).anyMatch(text -> text.contains("1,000억원"));
        var fallback = NewsAnswerContent.numericEvidenceResult(article, "투자금은 얼마야?");
        assertThat(fallback.matchesQuestion()).isFalse();
        assertThat(fallback.points()).isNotEmpty();
    }

    @Test
    void boundedRankingStillFindsRelevantNumbersAtTheEndOfALongArticle() {
        var source =
                new NewsArticle(
                        "long",
                        "v1",
                        "투자 계획",
                        java.util.stream.IntStream.range(1, 501)
                                        .mapToObj(i -> "일정은 " + i + "일이다.")
                                        .collect(java.util.stream.Collectors.joining(" "))
                                + " 투자금은 1,000억원이며 아직 확정되지 않았다.",
                        "테스트",
                        null,
                        LocalDateTime.of(2026, 11, 9, 9, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        5,
                        5,
                        5);
        assertThat(NewsAnswerContent.numericEvidence(source, "투자금은 얼마야?"))
                .singleElement()
                .asString()
                .contains("1,000억원", "확정되지");
        assertThat(NewsAnswerContent.numericEvidence(source)).hasSizeLessThanOrEqualTo(12);
    }

    @Test
    void generalEvidenceUsesCodePointsForThresholdAndTruncation() {
        var source =
                new NewsArticle(
                        "unicode",
                        "v1",
                        "설명",
                        "😀".repeat(321),
                        "테스트",
                        null,
                        LocalDateTime.of(2026, 11, 9, 9, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        5,
                        5,
                        5);
        var point = NewsAnswerContent.articleEvidence(source, "").get(0);
        assertThat(point).isEqualTo("😀".repeat(240) + "…");
    }

    @Test
    void targetedNumbersSearchTheWholeArticleAndKeepContextInsteadOfReturningEarlierDates() {
        String body =
                java.util.stream.IntStream.range(1, 16)
                                .mapToObj(i -> "일정은 " + i + "일이다.")
                                .collect(java.util.stream.Collectors.joining(" "))
                        + " 투자금은 1,000억원으로 검토 중이며 아직 확정되지 않았다. 설비 용량은 250MW다.";
        var source =
                new NewsArticle(
                        "a",
                        "v1",
                        "투자 계획",
                        body,
                        "테스트",
                        null,
                        LocalDateTime.of(2026, 11, 9, 9, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        5,
                        5,
                        5);
        assertThat(NewsAnswerContent.numericEvidence(source, "투자금은 얼마야?"))
                .hasSize(1)
                .first()
                .asString()
                .contains("1,000억원", "확정되지");
        assertThat(NewsAnswerContent.numericEvidence(source, "매출은 얼마야?")).isNotEmpty();
        assertThat(NewsAnswerContent.numericEvidence(source))
                .first()
                .asString()
                .contains("1,000억원");
        assertThat(NewsAnswerContent.asksForNumbers("설비 용량은 얼마야?")).isTrue();
    }

    @Test
    void percentagePointsRemainDistinctFromPercent() {
        var source =
                new NewsArticle(
                        "rates",
                        "v1",
                        "금리 변화",
                        "금리는 20%p 상승했다.",
                        "테스트",
                        null,
                        LocalDateTime.of(2026, 11, 9, 9, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        5,
                        5,
                        5);
        assertThat(new NewsAnswerContent("20%p", List.of(), null).numbersComeFrom(source)).isTrue();
        assertThat(new NewsAnswerContent("20%", List.of(), null).numbersComeFrom(source)).isFalse();
    }

    @Test
    void energyUnitsAndLongSentencesAreRetainedWithoutInventingMeasurements() {
        String body = "설비 설명이 이어진다 ".repeat(45) + "설비 용량은250MW로 확대할 계획이며 전력 저장량은 40MWh이다.";
        var energy =
                new NewsArticle(
                        "energy",
                        "v1",
                        "설비 계획",
                        body,
                        "테스트",
                        null,
                        java.time.LocalDateTime.of(2026, 11, 9, 9, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        5,
                        5,
                        5);
        assertThat(NewsAnswerContent.numericEvidence(energy))
                .isNotEmpty()
                .anyMatch(point -> point.contains("250MW"));
        assertThat(new NewsAnswerContent("용량 250MW", List.of(), null).numbersComeFrom(energy))
                .isTrue();
        assertThat(new NewsAnswerContent("용량 250GW", List.of(), null).numbersComeFrom(energy))
                .isFalse();
    }

    @Test
    void structuredPointsDeriveHistoryTextAndKeepOnlyExactSourceQuotes() {
        var result =
                NewsAnswerContent.decode(
                        """
                {"explanation":"","points":["생산능력을 올해의 2배로 확대할 계획이에요.","매출은 1,000억원이며 12.5% 증가했어요."],"quote":"공급 계약은 확정되지 않았다."}
                """,
                        article);
        assertThat(result.points()).hasSize(2);
        assertThat(result.explanation()).contains("2배", "12.5%");
        assertThat(result.quote()).isEqualTo("공급 계약은 확정되지 않았다.");
        assertThat(result.numbersComeFrom(article)).isTrue();
    }

    @Test
    void validParagraphSurvivesBlankOrNonTextualListPlaceholders() {
        var result =
                NewsAnswerContent.decode(
                        "{\"explanation\":\"관련 보유 종목을 확인해 주세요.\",\"points\":[\"\",null,{\"ticker\":\"invented\"}],\"quote\":null}",
                        article);
        assertThat(result.points()).isEmpty();
        assertThat(result.explanation()).isEqualTo("관련 보유 종목을 확인해 주세요.");
    }

    @Test
    void fabricatedQuoteIsDiscardedRatherThanDisplayedAsSource() {
        assertThat(
                        NewsAnswerContent.decode(
                                        "{\"explanation\":\"설명\",\"points\":[],\"quote\":\"투자 수익이 보장된다.\"}",
                                        article)
                                .quote())
                .isNull();
    }

    @Test
    void numericQuoteFallbackCopiesOnlyAnActualMatchingBodyFragment() {
        var result =
                new NewsAnswerContent("매출은 1,000억원이에요.", List.of("1,000억원"), null)
                        .withNumericSourceQuote(article);
        assertThat(result.quote()).isNotBlank().hasSizeLessThanOrEqualTo(100);
        assertThat(article.body()).contains(result.quote());
        assertThat(result.quote()).contains("1,000억원");
        assertThat(
                        new NewsAnswerContent("수치가 없어요.", List.of(), null)
                                .withNumericSourceQuote(article)
                                .quote())
                .isNull();
    }

    @Test
    void fabricatedNumberAndChangedUnitAreRejected() {
        assertThat(new NewsAnswerContent("매출은 2,000억원", List.of(), null).numbersComeFrom(article))
                .isFalse();
        assertThat(new NewsAnswerContent("12.5억원", List.of(), null).numbersComeFrom(article))
                .isFalse();
    }

    @Test
    void missingNumbersCanBeExplainedWithoutInventingValuesAndLegacyTemplatesStillWork() {
        var result = NewsAnswerContent.decode("이 기사에는 정리할 수 있는 수치 정보가 없어요.", article);
        assertThat(result.points()).isEmpty();
        assertThat(result.quote()).isNull();
        assertThat(result.numbersComeFrom(article)).isTrue();
    }

    @Test
    void numericEvidenceRetainsSourceUnitsAndPlansAndQuoteValidationRejectsLongCopies() {
        assertThat(NewsAnswerContent.numericEvidence(article))
                .anyMatch(s -> s.contains("2배") && s.contains("계획"))
                .anyMatch(s -> s.contains("1,000억원") && s.contains("12.5%"));
        assertThatThrownBy(
                        () ->
                                NewsAnswerContent.decode(
                                        "{\"explanation\":\"\",\"points\":[]}", article))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
