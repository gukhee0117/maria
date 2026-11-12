package com.app.maria.domain.stockpackage.type;

/** 패키지 점검에서 설명하는 지표 종류. 화면이 설명 묶음을 지표 카드와 짝지을 때 쓴다. */
public enum CheckMetric {
    AVG_GRADE("위험등급 가중평균"),
    PROFILE_RANGE("내 투자성향 범위"),
    VOLATILITY("연 변동성"),
    CONCENTRATION("종목 집중도"),
    TOP_ITEM("가장 큰 종목"),
    THEME("테마"),
    SECTOR("업종"),
    SURVEY_PROFILE("설문 투자성향");

    private final String title;

    CheckMetric(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
