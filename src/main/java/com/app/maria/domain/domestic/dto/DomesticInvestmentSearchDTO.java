package com.app.maria.domain.domestic.dto;

import com.app.maria.domain.domestic.type.DomesticStockStatus;
import java.time.LocalDate;
import java.util.List;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class DomesticInvestmentSearchDTO {
    private String keyword;
    private DomesticStockStatus status;
    private Boolean hasRestrictedHolding;
    private List<Long> unpurchasableAccountIds;
    private Boolean hasRecentBuy;
    private LocalDate recentBuySinceDate;
    @Builder.Default private String orderBy = "a.account_id";
    private int offset;
    private int size;
}
