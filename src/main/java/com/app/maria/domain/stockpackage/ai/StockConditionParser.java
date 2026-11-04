package com.app.maria.domain.stockpackage.ai;

import java.util.List;
import java.util.Optional;

public interface StockConditionParser {
    /**
     * 고객의 말을 조건 초안으로 바꾼다. AI 호출이 실패하거나 시간이 초과되거나 응답을 읽을 수 없으면 비어 있다. themeNames는 쓸 수 있는 테마 이름 목록이다.
     */
    Optional<ConditionDraft> parse(String text, List<String> themeNames);
}
