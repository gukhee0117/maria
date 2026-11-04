package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.ParseStatus;
import com.app.maria.domain.stockpackage.type.SortDirection;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 말로 조건 찾기 결과. 고객에게 보이는 문장(messages)은 검증을 통과한 값으로 문장은행에서 만들고, AI가 쓴 문장은 들어 있지 않다. filter는 status가
 * PARSED일 때만 있고 후보 보기 요청에 그대로 쓸 수 있는 값이다.
 */
@Getter
@Builder
public class StockConditionParseVO {
    private final ParseStatus status;
    private final Filter filter;

    /** 이해하지 못해 적용하지 않은 조건이 있었는가. */
    private final boolean partial;

    private final List<String> messages;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /** 검증을 마친 조건. itemCountMin·itemCountMax는 화면이 안내하는 데만 쓰고 후보 거르기에는 쓰지 않는다. */
    public record Filter(
            List<Type> productTypes,
            List<String> themes,
            Integer minGrade,
            Integer maxGrade,
            Integer itemCountMin,
            Integer itemCountMax,
            CandidateSort sort,
            SortDirection direction) {

        public boolean isEmpty() {
            return productTypes.isEmpty()
                    && themes.isEmpty()
                    && minGrade == null
                    && maxGrade == null
                    && itemCountMin == null
                    && itemCountMax == null
                    && sort == null
                    && direction == null;
        }
    }
}
