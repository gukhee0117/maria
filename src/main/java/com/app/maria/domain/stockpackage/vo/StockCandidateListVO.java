package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 후보 보기 결과. 순위·점수·이유는 없고 조건에 맞는 상품의 사실 정보만 담는다. */
@Getter
@Builder
public class StockCandidateListVO {
    private final List<Row> items;

    /** 조건에 맞는 전체 개수. items가 잘렸으면 items.size()보다 크다. */
    private final int totalCount;

    private final boolean truncated;
    private final boolean profileAvailable;
    private final Integer allowedMinGrade;
    private final boolean includeOutOfProfile;
    private final String sort;
    private final String direction;
    private final List<String> summary;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /**
     * 상품 한 줄. riskGrade·volatilityPercent가 null이면 아직 계산되지 않은 것이다. outOfProfile이 null이면 판단할 수 없는
     * 것이다. selectable이 false면 unselectableReason에 사유가 있다.
     */
    public record Row(
            Long domesticProductId,
            String ticker,
            String name,
            String market,
            Type type,
            String sectorName,
            List<String> themes,
            Integer riskGrade,
            BigDecimal volatilityPercent,
            Boolean outOfProfile,
            boolean selectable,
            String unselectableReason) {}
}
