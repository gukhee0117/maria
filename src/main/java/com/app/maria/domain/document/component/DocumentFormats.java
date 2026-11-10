package com.app.maria.domain.document.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 증빙 PDF에 찍는 금액·비율·구간 문자열 형식. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class DocumentFormats {

    static String label(LocalDate from, LocalDate to) {
        return from.getMonthValue() + "~" + to.getMonthValue() + "월";
    }

    static String won(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0");
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount);
    }

    /** 0.7442 -> 74.42%, 1.0000 -> 100%. 세율 규칙값(0.22)도 같은 방식이다. */
    static String percent(BigDecimal ratio) {
        BigDecimal percent =
                ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
