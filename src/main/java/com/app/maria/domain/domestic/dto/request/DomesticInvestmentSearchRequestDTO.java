package com.app.maria.domain.domestic.dto.request;

import com.app.maria.domain.domestic.dto.DomesticInvestmentSearchDTO;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.global.sort.OrderBySpec;
import java.util.Map;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class DomesticInvestmentSearchRequestDTO {
    private String keyword;
    private DomesticStockStatus status;
    private Boolean hasRestrictedHolding;
    private Boolean hasUnpurchasableHolding;
    private Boolean hasRecentBuy;
    private Integer recentBuyDays;
    private String sort;
    private String direction;
    private int page;
    private int size;

    /** 국내투자 목록의 정렬 키(화면 머리글) → 정렬 열. 여기 없는 키는 무시한다. */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of(
                    "accountNo", "a.account_no",
                    "customerName", "c.name",
                    "cashAmount", "a.amount",
                    "holdingCount", "holdingCount",
                    "hasRestrictedHolding", "hasRestrictedHolding");

    public DomesticInvestmentSearchDTO toDomesticInvestmentSearchDTO() {
        return DomesticInvestmentSearchDTO.builder()
                .keyword(keyword)
                .status(status)
                .hasRestrictedHolding(hasRestrictedHolding)
                .hasRecentBuy(hasRecentBuy)
                .orderBy(OrderBySpec.resolve(SORT_COLUMNS, sort, direction, "a.account_id"))
                .offset(page * size)
                .size(size)
                .build();
    }
}
