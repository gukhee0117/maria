package com.app.maria.domain.document.component;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 순매수 내역서 PDF에 찍는 값. 화면에 보이는 문자열(천 단위 쉼표·% 포함)로 미리 만들어 템플릿에는 계산이 없다. */
@Getter
@Builder
public class NetPurchaseStatementView {
    private final int taxYear;
    private final String issueNo;
    private final String issuedDate;
    private final String holderName;
    private final String accountNo;

    private final List<Period> periods;
    private final String totalNetBuy;
    private final String totalWeightedNetBuy;

    private final List<Trade> trades;

    @Getter
    @AllArgsConstructor
    public static class Period {
        private final String label;
        private final String netBuy;
        private final String weight;
        private final String weightedNetBuy;
    }

    @Getter
    @AllArgsConstructor
    public static class Trade {
        private final String tradeDate;
        private final String productLabel;
        private final String netBuy;
    }
}
