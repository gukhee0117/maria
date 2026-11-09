package com.app.maria.domain.news.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Structured presentation plus source-bound quotations; never executes model-supplied markup. */
public record NewsAnswerContent(String explanation, List<String> points, String quote) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Match percentage points (%p) before percent (%) to preserve the complete source unit.
    private static final Pattern NUMBER =
            Pattern.compile(
                    "(?<![A-Za-z\\p{N}])([+-]?\\d[\\d,]*(?:\\.\\d+)?)(\\s*(?:조|억|만|천)?\\s*(?:%p|%|퍼센트|GWh|MWh|kWh|GW|MW|kW|kV|㎿|㎾|km|㎞|㎡|달러|유로|원|배|명|개|건|대|주|톤|년|월|일|분기|위|시|분|초)?)");

    private static final Set<String> KEYWORD_STOP_WORDS =
            Set.of(
                    "기사", "뉴스", "기사에", "기사에서", "이기사", "이뉴스", "나온", "관련", "내용", "수치", "숫자", "알려줘",
                    "알려주세요", "정리해줘", "설명해줘", "무엇", "어떤", "왜", "뭐야", "얼마", "얼마야", "몇", "알려주기", "간단히",
                    "쉽게", "이유", "핵심", "뜻", "의미", "좀", "자세히", "정확히", "얼마인가요", "뭔데", "무슨", "그런거야",
                    "이게", "말이야");
    private static final Pattern WORD_PATTERN = Pattern.compile("[가-힣A-Za-z][가-힣A-Za-z0-9]*");
    private static final Pattern KEYWORD_SUFFIX =
            Pattern.compile("(?:에서는|에서|으로|이란|란|인가요|이야|은|는|이|가|을|를|의|도)$");

    public NewsAnswerContent {
        points = List.copyOf(points);
    }

    public static NewsAnswerContent decode(String raw, NewsArticle article) {
        String text = raw.strip();
        if (text.startsWith("```"))
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        if (!text.startsWith("{")) return new NewsAnswerContent(text, List.of(), null);
        try {
            JsonNode root = MAPPER.readTree(text);
            String explanation = root.path("explanation").asText("").strip();
            List<String> points = new ArrayList<>();
            var items = root.path("points");
            if (items.isArray())
                for (JsonNode item : items) {
                    // Some models retain an empty placeholder for an answer with no list.
                    // Ignore unusable list entries; never turn a model-supplied object into a
                    // stock reference or invent text. The explanation below must still be valid.
                    if (!item.isTextual()
                            || item.asText().isBlank()
                            || item.asText().length() > 400) continue;
                    if (points.size() >= 5) break;
                    points.add(item.asText().strip());
                }
            if (explanation.isBlank()) explanation = String.join(" ", points);
            if (explanation.isBlank() || explanation.length() > 2000)
                throw new IllegalArgumentException("Invalid explanation");
            String quote = root.path("quote").asText("").strip();
            if (quote.isBlank() || quote.length() > 200 || !article.body().contains(quote))
                quote = null;
            return new NewsAnswerContent(explanation, points, quote);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid news JSON", e);
        }
    }

    public static boolean asksForNumbers(String question) {
        return question.matches("(?s).*(?:숫자|수치|얼마|몇|규모|금액|용량|비율|증가율|감소율|퍼센트).*");
    }

    public static List<String> numericEvidence(NewsArticle article) {
        return numericEvidence(article, "");
    }

    public static List<String> numericEvidence(NewsArticle article, String question) {
        Set<String> evidence = new LinkedHashSet<>();
        for (String sentence :
                (article.title() + "\n" + article.body()).split("(?<=[.!?。])\\s+|\n+")) {
            sentence = sentence.strip();
            var matches = NUMBER.matcher(sentence);
            if (!matches.find()) continue;
            if (sentence.length() <= 320) evidence.add(sentence);
            else {
                do {
                    int start = Math.max(0, matches.start() - 80);
                    int end = Math.min(sentence.length(), Math.max(start + 240, matches.end()));
                    if (start > 0 && Character.isLowSurrogate(sentence.charAt(start))) start++;
                    if (end > start && Character.isHighSurrogate(sentence.charAt(end - 1))) end--;
                    evidence.add(
                            (start > 0 ? "…" : "")
                                    + sentence.substring(start, end).strip()
                                    + (end < sentence.length() ? "…" : ""));
                } while (matches.find());
            }
        }
        var keywords = questionKeywords(question);
        var ranked =
                evidence.stream()
                        .sorted(
                                Comparator.comparingInt((String text) -> relevance(text, keywords))
                                        .thenComparingInt(NewsAnswerContent::numericWeight)
                                        .reversed())
                        .toList();
        if (!keywords.isEmpty() && ranked.stream().anyMatch(text -> relevance(text, keywords) > 0))
            ranked = ranked.stream().filter(text -> relevance(text, keywords) > 0).toList();
        else if (!keywords.isEmpty()) return List.of();
        return ranked.stream().limit(12).toList();
    }

    private static int numericWeight(String text) {
        var matcher = NUMBER.matcher(text);
        int score = 0;
        while (matcher.find()) {
            String unit = matcher.group(2).strip();
            // Dates and identifiers are useful context but should not hide money/capacity/rates.
            if (!unit.isEmpty() && !unit.matches("(?:년|월|일|분기|시|분|초)")) score += 2;
        }
        return score;
    }

    public static List<String> questionKeywords(String question) {
        return WORD_PATTERN
                .matcher(question)
                .results()
                .map(m -> m.group().toLowerCase(Locale.ROOT))
                .filter(word -> !KEYWORD_STOP_WORDS.contains(word))
                .map(word -> KEYWORD_SUFFIX.matcher(word).replaceFirst(""))
                .filter(word -> word.length() >= 2 && !KEYWORD_STOP_WORDS.contains(word))
                .distinct()
                .toList();
    }

    private static int relevance(String text, List<String> keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        return (int) keywords.stream().filter(lower::contains).count();
    }

    public static List<String> articleEvidence(NewsArticle article, String question) {
        var keywords = questionKeywords(question);
        return Arrays.stream(article.body().split("(?<=[.!?。])\\s+|\n+"))
                .map(String::strip)
                .filter(text -> !text.isEmpty())
                .filter(text -> keywords.isEmpty() || relevance(text, keywords) > 0)
                .sorted(
                        Comparator.comparingInt((String text) -> relevance(text, keywords))
                                .thenComparingInt(NewsAnswerContent::numericWeight)
                                .reversed())
                .map(
                        text ->
                                text.length() <= 320
                                        ? text
                                        : text.substring(
                                                        0,
                                                        text.offsetByCodePoints(
                                                                0,
                                                                Math.min(
                                                                        240,
                                                                        text.codePointCount(
                                                                                0, text.length()))))
                                                + "…")
                .limit(3)
                .toList();
    }

    public boolean numbersComeFrom(NewsArticle article) {
        Set<String> source = new HashSet<>();
        NUMBER.matcher(article.title() + " " + article.body())
                .results()
                .forEach(m -> source.add(normalizeNumber(m.group())));
        String output = explanation + " " + String.join(" ", points);
        return NUMBER.matcher(output)
                .results()
                .allMatch(m -> source.contains(normalizeNumber(m.group())));
    }

    public NewsAnswerContent withNumericSourceQuote(NewsArticle article) {
        if (quote != null) return this;
        Set<String> values = new HashSet<>();
        NUMBER.matcher(explanation + " " + String.join(" ", points))
                .results()
                .forEach(m -> values.add(normalizeNumber(m.group())));
        var matches = NUMBER.matcher(article.body());
        while (matches.find()) {
            if (!values.contains(normalizeNumber(matches.group()))
                    || matches.group().length() > 100) continue;
            int start =
                    Math.max(
                            0, matches.start() - Math.max(0, (100 - matches.group().length()) / 2));
            int sentence =
                    Math.max(
                            article.body().lastIndexOf('\n', matches.start()),
                            article.body().lastIndexOf('.', matches.start()));
            if (sentence >= start && sentence < matches.start()) start = sentence + 1;
            int end = Math.min(article.body().length(), start + 100);
            if (start < end && Character.isLowSurrogate(article.body().charAt(start))) start++;
            if (end > start && Character.isHighSurrogate(article.body().charAt(end - 1))) end--;
            return new NewsAnswerContent(
                    explanation, points, article.body().substring(start, end).strip());
        }
        return this;
    }

    private static String normalizeNumber(String value) {
        var matcher = NUMBER.matcher(value);
        if (!matcher.matches()) return value.replaceAll("[,\\s]", "");
        String amount = matcher.group(1).replace(",", "");
        String unit = matcher.group(2).replaceAll("\\s", "");
        if (unit.equals("조원")) unit = "조";
        else if (unit.equals("억원")) unit = "억";
        else if (unit.equals("만원")) unit = "만";
        else if (unit.equals("천원")) unit = "천";
        else if (unit.equals("%") || unit.equals("퍼센트")) unit = "PERCENT";
        return amount + unit;
    }
}
