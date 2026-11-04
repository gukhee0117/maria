package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.SortDirection;
import com.app.maria.domain.stockpackage.vo.StockConditionParseVO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/**
 * 말로 조건 찾기 응답. filter의 productTypes·themes·minGrade·maxGrade·sort·direction은 후보 보기 요청에 그대로 쓸 수 있다.
 */
@Getter
public class StockConditionParseResponseDTO {
    private final String status;
    private final FilterResponse filter;
    private final boolean partial;
    private final List<String> messages;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public StockConditionParseResponseDTO(StockConditionParseVO vo) {
        this.status = vo.getStatus().name();
        this.filter = vo.getFilter() == null ? null : new FilterResponse(vo.getFilter());
        this.partial = vo.isPartial();
        this.messages = vo.getMessages();
        this.disclaimer = vo.getDisclaimer();
        this.checkedAt = vo.getCheckedAt();
    }

    @Getter
    public static class FilterResponse {
        private final List<Type> productTypes;
        private final List<String> themes;
        private final Integer minGrade;
        private final Integer maxGrade;
        private final Integer itemCountMin;
        private final Integer itemCountMax;
        private final CandidateSort sort;
        private final SortDirection direction;

        public FilterResponse(StockConditionParseVO.Filter vo) {
            this.productTypes = vo.productTypes();
            this.themes = vo.themes();
            this.minGrade = vo.minGrade();
            this.maxGrade = vo.maxGrade();
            this.itemCountMin = vo.itemCountMin();
            this.itemCountMax = vo.itemCountMax();
            this.sort = vo.sort();
            this.direction = vo.direction();
        }
    }
}
