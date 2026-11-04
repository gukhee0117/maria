package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.ConditionDraft;
import com.app.maria.domain.stockpackage.ai.ConditionDraft.Intent;
import com.app.maria.domain.stockpackage.service.StockConditionNormalizer.Normalized;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.SortDirection;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockConditionNormalizerTest {
    private static final List<String> THEMES = List.of("2차전지", "반도체", "배당", "AI 소프트웨어");

    private static ConditionDraft draft(
            List<Type> types,
            List<String> themes,
            Integer minGrade,
            Integer maxGrade,
            Integer countMin,
            Integer countMax) {
        return new ConditionDraft(
                Intent.FILTER,
                types,
                themes,
                minGrade,
                maxGrade,
                countMin,
                countMax,
                CandidateSort.NAME,
                SortDirection.ASC);
    }

    @Test
    @DisplayName("올바른 조건은 그대로 통과한다")
    void valid() {
        Normalized n =
                StockConditionNormalizer.normalize(
                        draft(List.of(Type.FUND), List.of("배당"), 2, 4, 3, 5), THEMES);

        assertThat(n.partial()).isFalse();
        assertThat(n.filter().productTypes()).containsExactly(Type.FUND);
        assertThat(n.filter().themes()).containsExactly("배당");
        assertThat(n.filter().minGrade()).isEqualTo(2);
        assertThat(n.filter().maxGrade()).isEqualTo(4);
        assertThat(n.filter().itemCountMin()).isEqualTo(3);
        assertThat(n.filter().itemCountMax()).isEqualTo(5);
    }

    @Test
    @DisplayName("테마는 공백·대소문자 차이를 무시하고 목록의 이름으로 바꾼다")
    void themeCanonicalized() {
        Normalized n =
                StockConditionNormalizer.normalize(
                        draft(null, List.of("ai소프트웨어", " 반도체 "), null, null, null, null), THEMES);

        assertThat(n.filter().themes()).containsExactly("AI 소프트웨어", "반도체");
        assertThat(n.partial()).isFalse();
    }

    @Test
    @DisplayName("목록에 없는 테마는 버리고 일부만 적용했다고 표시한다")
    void unknownThemeDropped() {
        Normalized n =
                StockConditionNormalizer.normalize(
                        draft(null, List.of("배당", "삼성전자를 사세요", "005930"), null, null, null, null),
                        THEMES);

        assertThat(n.filter().themes()).containsExactly("배당");
        assertThat(n.partial()).isTrue();
    }

    @Test
    @DisplayName("범위를 벗어난 등급과 종목 수, 뒤집힌 범위는 버린다")
    void invalidNumbers() {
        Normalized grade =
                StockConditionNormalizer.normalize(draft(null, null, 0, 7, null, null), THEMES);
        assertThat(grade.filter().minGrade()).isNull();
        assertThat(grade.filter().maxGrade()).isNull();
        assertThat(grade.partial()).isTrue();

        Normalized reversed =
                StockConditionNormalizer.normalize(draft(null, null, 5, 2, 9, 3), THEMES);
        assertThat(reversed.filter().minGrade()).isNull();
        assertThat(reversed.filter().itemCountMin()).isNull();
        assertThat(reversed.partial()).isTrue();

        Normalized tooMany =
                StockConditionNormalizer.normalize(draft(null, null, null, null, 1, 500), THEMES);
        assertThat(tooMany.filter().itemCountMax()).isNull();
        assertThat(tooMany.partial()).isTrue();
    }

    @Test
    @DisplayName("정렬 기준 없이 방향만 있으면 방향도 버린다")
    void directionWithoutSort() {
        Normalized n =
                StockConditionNormalizer.normalize(
                        new ConditionDraft(
                                Intent.FILTER,
                                null,
                                List.of("배당"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                SortDirection.DESC),
                        THEMES);

        assertThat(n.filter().sort()).isNull();
        assertThat(n.filter().direction()).isNull();

        Normalized withSort =
                StockConditionNormalizer.normalize(
                        draft(null, List.of("배당"), null, null, null, null), THEMES);
        assertThat(withSort.filter().sort()).isEqualTo(CandidateSort.NAME);
        assertThat(withSort.filter().direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    @DisplayName("테마는 최대 5개까지만 적용한다")
    void themeLimit() {
        List<String> many = List.of("가", "나", "다", "라", "마", "바", "사");
        Normalized n =
                StockConditionNormalizer.normalize(draft(null, many, null, null, null, null), many);

        assertThat(n.filter().themes()).hasSize(5);
    }

    @Test
    @DisplayName("null이 섞인 초안도 오류 없이 처리하고 조건이 없으면 비어 있다")
    void nullsAndEmpty() {
        Normalized n =
                StockConditionNormalizer.normalize(
                        new ConditionDraft(
                                Intent.FILTER,
                                Arrays.asList(Type.STOCK, null),
                                Arrays.asList("배당", null),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        THEMES);
        assertThat(n.filter().productTypes()).containsExactly(Type.STOCK);
        assertThat(n.filter().themes()).containsExactly("배당");

        Normalized empty =
                StockConditionNormalizer.normalize(
                        new ConditionDraft(
                                Intent.OTHER, null, null, null, null, null, null, null, null),
                        THEMES);
        assertThat(empty.filter().isEmpty()).isTrue();
        assertThat(empty.partial()).isFalse();
    }
}
