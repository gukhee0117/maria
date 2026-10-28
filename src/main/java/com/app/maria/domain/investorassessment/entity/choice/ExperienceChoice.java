package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ExperienceChoice implements ScoredChoice {
    HIGH_RISK_PRODUCTS("선물·옵션, 주식 신용거래, 레버리지·인버스 ETF", 10),
    STOCKS_AND_EQUITY_FUNDS("주식, 원금 비보장형 ELS, 주식형 펀드", 8),
    PRINCIPAL_GUARANTEED_AND_MIXED("원금보장형 ELS, 혼합형 펀드", 6),
    ELB_AND_BOND_FUNDS("ELB, 채권형 펀드", 4),
    DEPOSITS("예금·적금", 2),
    NONE("투자 경험이 없어요", 0);

    private final String label;
    private final int score;
}
