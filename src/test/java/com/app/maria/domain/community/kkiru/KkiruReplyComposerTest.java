package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.community.entity.CommunityContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KkiruReplyComposerTest {
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private KkiruReplyComposer composer;

    @Test
    @DisplayName("문장을 공백으로 이어 붙여 제목 없는 문단 하나로 만들고 근거 목록(라벨·링크)을 만든다")
    void composes() throws Exception {
        Optional<KkiruReplyComposer.Composed> composed =
                composer.compose(KkiruTestData.twoSentenceAnswer());

        assertThat(composed).isPresent();
        assertThat(composed.get().content())
                .isEqualTo("최근 30일 동안 공시가 3건 올라왔어요. 자체 산정 위험등급은 2등급이에요.");
        JsonNode sources = objectMapper.readTree(composed.get().sourcesJson());
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).get("id").asText()).isEqualTo("DART:1");
        assertThat(sources.get(0).get("label").asText()).isEqualTo("최근 30일 공시 3건");
        assertThat(sources.get(0).get("url").asText()).isEqualTo("https://dart.fss.or.kr/x");
        assertThat(sources.get(1).get("id").asText()).isEqualTo("RISK:7");
        assertThat(sources.get(1).get("url").isNull()).isTrue();
    }

    @Test
    @DisplayName("같은 근거를 여러 문장이 인용해도 근거 목록에는 한 번만 들어가고, 쓰이지 않은 근거는 빠진다")
    void sourcesAreUniqueAndUsedOnly() throws Exception {
        KkiruAnswer answer =
                new KkiruAnswer(
                        List.of(
                                new KkiruSentence("첫 문장 3건", List.of("D")),
                                new KkiruSentence("둘째 문장 3건", List.of("D"))),
                        Map.of(
                                "D", new Evidence("D", "공시 3건", null),
                                "UNUSED", new Evidence("UNUSED", "안 쓴 근거", null)));

        JsonNode sources =
                objectMapper.readTree(composer.compose(answer).orElseThrow().sourcesJson());

        assertThat(sources).hasSize(1);
    }

    @Test
    @DisplayName("500자를 넘으면 뒤 문장부터 빼서 500자 안에 맞추고, 뺀 문장의 근거도 뺀다")
    void dropsTrailingSentences() {
        KkiruAnswer answer =
                new KkiruAnswer(
                        List.of(
                                new KkiruSentence("가".repeat(300), List.of("A")),
                                new KkiruSentence("나".repeat(150), List.of("B")),
                                new KkiruSentence("다".repeat(150), List.of("C"))),
                        Map.of(
                                "A", new Evidence("A", "근거", null),
                                "B", new Evidence("B", "근거", null),
                                "C", new Evidence("C", "근거", null)));

        KkiruReplyComposer.Composed composed = composer.compose(answer).orElseThrow();

        assertThat(composed.content()).contains("나").doesNotContain("다");
        assertThat(composed.content().codePointCount(0, composed.content().length()))
                .isLessThanOrEqualTo(CommunityContent.MAX_LENGTH);
        assertThat(composed.sourcesJson()).doesNotContain("\"C\"");
    }

    @Test
    @DisplayName("첫 문장조차 500자에 들어가지 않으면 만들 수 없다")
    void impossible() {
        assertThat(composer.compose(KkiruTestData.answer("가".repeat(600), "A", "근거"))).isEmpty();
    }
}
