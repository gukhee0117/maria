package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KkiruFormatTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "79318746000000|79조 3,187억원",
                "14999999992000|15조원",
                "-7730313000000|-7조 7,303억원",
                "0|0원",
                "99999999|99,999,999원",
                "100000000|1억원",
                "149999999|1억원",
                "150000000|2억원",
                "999999999999|1조원",
                "1000000000000|1조원",
                "1000050000000|1조 1억원",
                "999900000000|9,999억원",
                "50000|50,000원",
                "-5000|-5,000원",
                "40023070290000|40조 231억원"
            })
    @DisplayName("원 단위 금액: 1조 이상 X조 Y억원, 1억 이상 Y억원, 1억 미만 쉼표 원 — 억 단위 반올림, 억이 0이면 생략(경계 포함)")
    void won(String raw, String expected) {
        assertThat(KkiruFormat.won(new BigDecimal(raw))).isEqualTo(expected);
    }

    @Test
    @DisplayName("백만원 단위는 먼저 원으로 바꾼다")
    void millionWon() {
        assertThat(KkiruFormat.millionWon(new BigDecimal("2095133"))).isEqualTo("2조 951억원");
        assertThat(KkiruFormat.millionWon(new BigDecimal("825721"))).isEqualTo("8,257억원");
    }

    @Test
    @DisplayName("퍼센트와 수량")
    void percentAndCount() {
        assertThat(KkiruFormat.percent(new BigDecimal("45.945"))).isEqualTo("45.9%");
        assertThat(KkiruFormat.percent(new BigDecimal("4.90"))).isEqualTo("4.9%");
        assertThat(KkiruFormat.percent(new BigDecimal("0.04"))).isEqualTo("0.0%");
        assertThat(KkiruFormat.count(new BigDecimal("22000000"))).isEqualTo("22,000,000");
    }

    @Test
    @DisplayName("증감률: 소수 첫째 자리, 비교값이 0·음수면 비교 불가, 적자에서 흑자로는 따로 서술")
    void change() {
        assertThat(
                        KkiruFormat.change(
                                new BigDecimal("79318746000000"), new BigDecimal("22231952000000")))
                .isEqualTo("+256.8%");
        assertThat(KkiruFormat.change(new BigDecimal("96"), new BigDecimal("100")))
                .isEqualTo("-4.0%");
        assertThat(KkiruFormat.change(new BigDecimal("100"), new BigDecimal("100")))
                .isEqualTo("+0.0%");
        assertThat(KkiruFormat.change(new BigDecimal("5"), BigDecimal.ZERO)).isNull();
        assertThat(KkiruFormat.change(new BigDecimal("-3"), new BigDecimal("-5"))).isNull();
        assertThat(KkiruFormat.change(new BigDecimal("0"), new BigDecimal("-5"))).isNull();
        assertThat(KkiruFormat.change(new BigDecimal("8"), new BigDecimal("-5")))
                .isEqualTo(KkiruFormat.TURNED_PROFIT);
    }

    @Test
    @DisplayName("값이 '-'·빈 문자열·null이면 없는 값이고, 쉼표는 지운다")
    void number() {
        assertThat(KkiruFormat.number("-")).isEmpty();
        assertThat(KkiruFormat.number("")).isEmpty();
        assertThat(KkiruFormat.number("  ")).isEmpty();
        assertThat(KkiruFormat.number(null)).isEmpty();
        assertThat(KkiruFormat.number("abc")).isEmpty();
        assertThat(KkiruFormat.number("1,234")).contains(new BigDecimal("1234"));
        assertThat(KkiruFormat.number("-9,112,428")).contains(new BigDecimal("-9112428"));
    }

    @Test
    @DisplayName("날짜: DART의 01월을 1월로, 접수번호 앞 8자리는 접수일로")
    void dates() {
        assertThat(KkiruFormat.date("2026년 01월 30일")).contains("2026년 1월 30일");
        assertThat(KkiruFormat.date("2026년 12월 05일")).contains("2026년 12월 5일");
        assertThat(KkiruFormat.date("-")).isEmpty();
        assertThat(KkiruFormat.date((String) null)).isEmpty();
        assertThat(KkiruFormat.date(LocalDate.of(2026, 11, 3))).isEqualTo("2026년 11월 3일");
        assertThat(KkiruFormat.dateOfCompact("20261028000130")).contains("2026년 10월 28일");
        assertThat(KkiruFormat.dateOfCompact("20261029")).contains("2026년 10월 29일");
        assertThat(KkiruFormat.dateOfCompact("abc")).isEmpty();
        assertThat(KkiruFormat.dateOfCompact(null)).isEmpty();
    }

    @Test
    @DisplayName("기간: 같은 해면 뒤 연도를 생략하고, 같은 날이면 날짜 하나, 모양이 다르면 없음")
    void period() {
        assertThat(KkiruFormat.period("2026년 01월 30일", "2026년 04월 29일"))
                .contains("2026년 1월 30일부터 4월 29일까지");
        assertThat(KkiruFormat.period("2025년 12월 20일", "2026년 02월 03일"))
                .contains("2025년 12월 20일부터 2026년 2월 3일까지");
        assertThat(KkiruFormat.period("2025년 07월 28일", "2025년 07월 28일")).contains("2025년 7월 28일");
        assertThat(KkiruFormat.period("-", "2026년 04월 29일")).isEmpty();
    }
}
