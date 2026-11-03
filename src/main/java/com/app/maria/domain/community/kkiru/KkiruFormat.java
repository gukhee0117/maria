package com.app.maria.domain.community.kkiru;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 근거 문장에 들어가는 숫자·날짜를 사람이 읽는 형태로 만든다(설계 4.3). LLM이 숫자를 바꾸면 검증기가 답을 버리므로, 읽는 형태는 코드가 미리 만들어 근거에 적어
 * 둔다. 금액은 BigDecimal이고 반올림은 HALF_UP이다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class KkiruFormat {
    /** 증감률 대신 쓰는 서술: 비교값이 음수(적자)이고 이번 값이 양수일 때. */
    static final String TURNED_PROFIT = "적자에서 흑자로 전환";

    private static final BigDecimal EOK = new BigDecimal("100000000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern KOREAN_DATE =
            Pattern.compile("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");

    /** 값이 비었거나 "-"이거나 null이면 empty. 천 단위 쉼표는 지운다. */
    static Optional<BigDecimal> number(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().replace(",", "");
        if (text.isEmpty() || text.equals("-")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** JSON 행의 문자열 필드. 없거나 "-"·빈 값이면 empty. */
    static Optional<String> text(JsonNode row, String field) {
        String value = row.path(field).asText("").trim();
        return value.isEmpty() || value.equals("-") ? Optional.empty() : Optional.of(value);
    }

    /**
     * 원 단위 금액을 읽는 금액으로. 1조 이상은 {@code X조 Y,YYY억원}, 1억 이상 1조 미만은 {@code Y,YYY억원}, 1억 미만은 쉼표 원. 억 단위로
     * 반올림하고 억이 0이면 억을 생략한다. 음수는 앞에 {@code -}.
     */
    static String won(BigDecimal won) {
        String sign = won.signum() < 0 ? "-" : "";
        BigDecimal abs = won.abs();
        if (abs.compareTo(EOK) < 0) {
            return sign + comma(abs.setScale(0, RoundingMode.HALF_UP).longValueExact()) + "원";
        }
        long eok = abs.divide(EOK, 0, RoundingMode.HALF_UP).longValueExact();
        long jo = eok / 10_000;
        long rest = eok % 10_000;
        String body;
        if (jo == 0) {
            body = comma(eok) + "억원";
        } else if (rest == 0) {
            body = comma(jo) + "조원";
        } else {
            body = comma(jo) + "조 " + comma(rest) + "억원";
        }
        return sign + body;
    }

    /** 백만원 단위 값(배당 총액 등)을 먼저 원으로 바꿔 {@link #won}과 같은 규칙으로. */
    static String millionWon(BigDecimal million) {
        return won(million.multiply(MILLION));
    }

    /** 퍼센트 값은 소수 첫째 자리로 반올림한다. 45.945 → 45.9% */
    static String percent(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 수량(주): 천 단위 쉼표 정수. */
    static String count(BigDecimal value) {
        return comma(value.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /**
     * 증감률: (이번 − 비교) ÷ |비교| × 100, 소수 첫째 자리({@code +12.3%}, {@code -4.0%}). 비교값이 음수에서 이번 값이 양수로 바뀌면
     * {@link #TURNED_PROFIT}. 그 밖에 비교값이 0이거나 음수이면 계산하지 않고 null(비교 불가)이다.
     */
    static String change(BigDecimal current, BigDecimal base) {
        if (base.signum() > 0) {
            BigDecimal pct =
                    current.subtract(base).multiply(HUNDRED).divide(base, 1, RoundingMode.HALF_UP);
            return (pct.signum() >= 0 ? "+" : "") + pct.toPlainString() + "%";
        }
        if (base.signum() < 0 && current.signum() > 0) {
            return TURNED_PROFIT;
        }
        return null;
    }

    /** DART의 {@code 2026년 01월 30일}을 0 없이 {@code 2026년 1월 30일}로. 모양이 다르면 empty. */
    static Optional<String> date(String koreanDate) {
        if (koreanDate == null) {
            return Optional.empty();
        }
        Matcher m = KOREAN_DATE.matcher(koreanDate);
        return m.find()
                ? Optional.of(
                        m.group(1)
                                + "년 "
                                + Integer.parseInt(m.group(2))
                                + "월 "
                                + Integer.parseInt(m.group(3))
                                + "일")
                : Optional.empty();
    }

    static String date(LocalDate date) {
        return date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
    }

    /** {@code 20261028} 또는 접수번호 앞 8자리({@code 20261028000130})를 날짜로. 모양이 다르면 empty. */
    static Optional<String> dateOfCompact(String compact) {
        if (compact == null
                || compact.length() < 8
                || !compact.substring(0, 8).chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(
                date(
                        LocalDate.of(
                                Integer.parseInt(compact.substring(0, 4)),
                                Integer.parseInt(compact.substring(4, 6)),
                                Integer.parseInt(compact.substring(6, 8)))));
    }

    /**
     * 기간 {@code 2026년 01월 30일}~{@code 2026년 04월 29일}을 {@code 2026년 1월 30일부터 4월 29일까지}로(같은 해면 뒤의 연도
     * 생략). 시작일과 종료일이 같으면 그 날짜 하나. 모양이 다르면 empty.
     */
    static Optional<String> period(String from, String to) {
        Optional<String> start = date(from);
        Optional<String> end = date(to);
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        if (start.get().equals(end.get())) {
            return start;
        }
        String endText =
                start.get().substring(0, 4).equals(end.get().substring(0, 4))
                        ? end.get().substring(end.get().indexOf('년') + 2)
                        : end.get();
        return Optional.of(start.get() + "부터 " + endText + "까지");
    }

    static String dartLink(String receiptNo) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receiptNo;
    }

    private static String comma(long value) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(value);
    }
}
