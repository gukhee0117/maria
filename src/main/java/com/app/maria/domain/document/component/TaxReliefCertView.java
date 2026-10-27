package com.app.maria.domain.document.component;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 증빙 PDF에 찍는 값. 화면에 보이는 문자열(천 단위 쉼표·% 포함)로 미리 만들어 템플릿에는 계산이 없다. */
@Getter
@Builder
public class TaxReliefCertView {
    private final int taxYear;
    private final String issueNo;
    private final String issuedDate;
    private final String holderName;
    private final String accountNo;
    private final String openedDate;
    private final String limitAmount;

    private final List<Period> periods;
    private final String totalSell;
    private final String totalGain;
    private final String totalWeightedSell;
    private final String totalWeightedGain;

    private final String weightedGain;
    private final String externalAmount;
    private final String adjustRatio;
    private final String finalDeduction;
    private final String originalGain;
    private final String basicDeduction;
    private final String taxRate;
    private final String finalTax;

    /** 조정비율이 100% 미만이면 RIA 외 계좌 순매수로 공제가 줄었다는 안내를 넣는다. */
    private final boolean reducedByExternal;

    @Getter
    @AllArgsConstructor
    public static class Period {
        private final String label;
        private final String sell;
        private final String gain;
        private final String weight;
        private final String weightedSell;
        private final String weightedGain;
    }
}
