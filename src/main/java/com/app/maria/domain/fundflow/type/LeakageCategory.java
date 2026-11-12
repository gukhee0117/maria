package com.app.maria.domain.fundflow.type;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 혜택 누수 5분류. 위에서부터 먼저 걸리는 하나로 계좌를 배정한다. */
@Getter
@RequiredArgsConstructor
public enum LeakageCategory {
    NO_SELL("매도 없음", "아직 RIA 계좌에서 해외주식을 팔지 않아 계산할 혜택이 없어요"),
    BENEFIT_CANCELED("혜택 취소(조기 인출)", "1년이 지나기 전에 원금을 인출해 세금 혜택이 취소됐어요"),
    NO_IMPACT("영향 없음", "세금 혜택이 그대로 유지되고 있어요"),
    PARTIAL_REDUCED("부분 축소", "다른 계좌의 해외주식 매수로 혜택이 일부 줄었어요"),
    EXTERNAL_BUY_OFFSET("타 계좌 순매수로 소멸", "다른 계좌의 해외주식 매수로 혜택이 모두 사라졌어요");

    private static final String BENEFIT_IMPOSSIBLE = "IMPOSSIBLE";

    private final String label;
    private final String customerMessage;

    /** SQL의 CASE(FundFlowMapper.xml selectLeakage)와 같은 순서·조건으로 분류한다. */
    public static LeakageCategory classify(
            BigDecimal weightedSell, String benefit, BigDecimal adjustRatio) {
        if (weightedSell.signum() == 0) {
            return NO_SELL;
        }
        if (BENEFIT_IMPOSSIBLE.equals(benefit)) {
            return BENEFIT_CANCELED;
        }
        if (adjustRatio.compareTo(BigDecimal.ONE) == 0) {
            return NO_IMPACT;
        }
        if (adjustRatio.signum() > 0) {
            return PARTIAL_REDUCED;
        }
        return EXTERNAL_BUY_OFFSET;
    }
}
