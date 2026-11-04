package com.app.maria.domain.stockpackage.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 체결 주문 수와 처음 체결 시각. 주문이 없으면 orderCount는 0이고 시각은 null이다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradePeriodVO {
    private int orderCount;
    private LocalDateTime firstProcessedAt;
}
