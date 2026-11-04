package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.ConditionDraft;
import com.app.maria.domain.stockpackage.type.SortDirection;
import com.app.maria.domain.stockpackage.vo.StockConditionParseVO.Filter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI가 만든 조건 초안을 서버가 믿고 쓸 수 있는 값으로 걸러낸다. 허용된 값 밖의 것은 모두 버리고, 버린 것이 있으면 partial로 표시한다. AI의 응답은 이 단계를
 * 거쳐야만 조건이 된다.
 */
public final class StockConditionNormalizer {
    static final int MAX_THEMES = 5;
    static final int MAX_ITEM_COUNT = 50;

    private StockConditionNormalizer() {}

    /** filter가 비어 있으면 쓸 수 있는 조건이 하나도 없다는 뜻이다. */
    public record Normalized(Filter filter, boolean partial) {}

    public static Normalized normalize(ConditionDraft draft, List<String> themeNames) {
        boolean partial = false;

        Set<Type> types = new LinkedHashSet<>();
        if (draft.productTypes() != null) {
            for (Type t : draft.productTypes()) {
                if (t != null) {
                    types.add(t);
                }
            }
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (String name : themeNames) {
            canonical.put(key(name), name);
        }
        Set<String> themes = new LinkedHashSet<>();
        if (draft.themes() != null) {
            for (String raw : draft.themes()) {
                String match = raw == null ? null : canonical.get(key(raw));
                if (match == null) {
                    partial = true; // 목록에 없는 테마는 적용하지 않는다.
                } else if (themes.size() < MAX_THEMES) {
                    themes.add(match);
                }
            }
        }

        Integer minGrade = draft.minGrade();
        Integer maxGrade = draft.maxGrade();
        boolean gradeInvalid =
                outOfRange(minGrade, 1, 6)
                        || outOfRange(maxGrade, 1, 6)
                        || (minGrade != null && maxGrade != null && minGrade > maxGrade);
        if (gradeInvalid) {
            minGrade = null;
            maxGrade = null;
            partial = true;
        }

        Integer countMin = draft.itemCountMin();
        Integer countMax = draft.itemCountMax();
        boolean countInvalid =
                outOfRange(countMin, 1, MAX_ITEM_COUNT)
                        || outOfRange(countMax, 1, MAX_ITEM_COUNT)
                        || (countMin != null && countMax != null && countMin > countMax);
        if (countInvalid) {
            countMin = null;
            countMax = null;
            partial = true;
        }

        // 정렬 기준 없이 방향만 있으면 어떤 기준의 방향인지 알 수 없으므로 방향도 버린다.
        SortDirection direction = draft.sort() == null ? null : draft.direction();

        Filter filter =
                new Filter(
                        List.copyOf(new ArrayList<>(types)),
                        List.copyOf(new ArrayList<>(themes)),
                        minGrade,
                        maxGrade,
                        countMin,
                        countMax,
                        draft.sort(),
                        direction);
        return new Normalized(filter, partial);
    }

    private static boolean outOfRange(Integer value, int min, int max) {
        return value != null && (value < min || value > max);
    }

    /** 공백과 대소문자 차이만 무시하고 같은 이름으로 본다. */
    private static String key(String name) {
        return name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
