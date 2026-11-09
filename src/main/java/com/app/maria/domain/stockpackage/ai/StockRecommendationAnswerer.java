package com.app.maria.domain.stockpackage.ai;

import java.util.Optional;

public interface StockRecommendationAnswerer {
    /**
     * 추천 종목의 사실(facts)만 근거로 질문에 답한다. 호출이 실패하거나 시간이 초과되면 비어 있다. 동시 호출이 상한에 차서 받지 못하면 빈 값이 아니라 {@link
     * AiBusyException}을 던진다. facts는 서버가 만든 줄 단위 사실 목록이다.
     */
    Optional<String> answer(String question, String facts);
}
