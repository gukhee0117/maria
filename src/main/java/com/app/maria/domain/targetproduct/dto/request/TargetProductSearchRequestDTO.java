package com.app.maria.domain.targetproduct.dto.request;

import com.app.maria.domain.targetproduct.dto.TargetProductSearchDTO;
import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import com.app.maria.global.sort.OrderBySpec;
import java.util.Map;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class TargetProductSearchRequestDTO {
    private String customerName;
    private StockType stockType;
    private Boolean isTarget;
    private TradeType tradeType;
    private Boolean todayOnly;
    private Boolean inheritanceGiftOnly;

    private String sort;

    private String direction;

    private int page;

    private int size;

    /** 판정 목록의 정렬 키(화면 머리글) → 정렬 열. 여기 없는 키는 무시한다. */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of(
                    "judgedAt", "j.judged_at",
                    "customerName", "c.name",
                    "stockType", "j.stock_type",
                    "fundName", "COALESCE(j.fund_name, j.ticker)",
                    "tradeType", "j.trade_type",
                    "amount", "j.amount",
                    "netBuyAmount", "j.net_buy_amount",
                    "isTarget", "j.is_target");

    public TargetProductSearchDTO toTargetProductSearchDTO() {
        return TargetProductSearchDTO.builder()
                .customerName(customerName)
                .stockType(stockType)
                .isTarget(isTarget)
                .tradeType(tradeType)
                .inheritanceGiftOnly(inheritanceGiftOnly)
                .orderBy(
                        OrderBySpec.resolve(
                                SORT_COLUMNS,
                                sort,
                                direction,
                                "j.judged_at DESC, j.judgement_id DESC"))
                .offset(page * size)
                .size(size)
                .build();
    }
}
