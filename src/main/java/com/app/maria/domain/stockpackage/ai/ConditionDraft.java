package com.app.maria.domain.stockpackage.ai;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.SortDirection;
import java.util.List;

/**
 * AI가 고객의 말에서 뽑은 조건 초안. 이 스키마에는 종목코드·종목명·자유 문장 필드가 없어서 AI가 상품을 지명하거나 고객에게 보일 문장을 만들 수 없다. 값은 서버가 다시
 * 검증해서 쓴다.
 */
public record ConditionDraft(
        Intent intent,
        List<Type> productTypes,
        List<String> themes,
        Integer minGrade,
        Integer maxGrade,
        Integer itemCountMin,
        Integer itemCountMax,
        CandidateSort sort,
        SortDirection direction) {

    public enum Intent {
        FILTER,
        ADVICE_REQUEST,
        OTHER
    }
}
