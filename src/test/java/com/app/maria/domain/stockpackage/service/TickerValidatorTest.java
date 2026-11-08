package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.global.exception.AppException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TickerValidatorTest {

    @Test
    @DisplayName("공백을 떼고 중복을 없애며 요청 순서를 지킨다")
    void trimsDedupesAndKeepsOrder() {
        assertThat(TickerValidator.validate(List.of(" 005930", "000660", "005930 ", "0001A0")))
                .containsExactly("005930", "000660", "0001A0");
    }

    @Test
    @DisplayName("종목이 없거나 null이거나 20개를 넘으면 거절한다")
    void rejectsBadCount() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= TickerValidator.MAX_TICKERS; i++) {
            tooMany.add(String.format("%06d", i));
        }
        assertThatThrownBy(() -> TickerValidator.validate(null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> TickerValidator.validate(List.of()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> TickerValidator.validate(tooMany))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("코드 형식이 틀리거나 null·빈 값이 섞이면 거절한다")
    void rejectsBadCode() {
        assertThatThrownBy(() -> TickerValidator.validate(List.of("12345")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> TickerValidator.validate(List.of("00593a")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> TickerValidator.validate(Arrays.asList("005930", null)))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> TickerValidator.validate(List.of("  ")))
                .isInstanceOf(AppException.class);
    }
}
