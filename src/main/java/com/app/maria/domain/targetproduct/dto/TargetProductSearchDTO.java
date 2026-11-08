package com.app.maria.domain.targetproduct.dto;

import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class TargetProductSearchDTO {
    private String customerName;
    private StockType stockType;
    private Boolean isTarget;
    private TradeType tradeType;
    private Boolean inheritanceGiftOnly;
    private LocalDateTime judgedAtFrom;
    private LocalDateTime judgedAtTo;
    @Builder.Default private String orderBy = "j.judged_at DESC, j.judgement_id DESC";
    private int offset;
    private int size;
}
