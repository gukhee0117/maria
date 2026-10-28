package com.app.maria.domain.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaxBriefingResponseValidatorTest {

    private final TaxBriefingResponseValidator validator = new TaxBriefingResponseValidator();

    @Test
    void 제공된_금액과_날짜만_사용한_설명은_허용한다() {
        String explanation =
                "현재 예상 감면액은 200,000원이고, 정산 반영 시 50,000원이 추가로 예상돼요. "
                        + "예상 확정일은 2026년 10월 30일이며 실제 신고 세액은 달라질 수 있어요.";

        assertThat(validator.isValid(explanation, briefing())).isTrue();
    }

    @Test
    void 입력에_없는_금액을_만든_설명은_거부한다() {
        String explanation = "현재 예상 감면액은 210,000원이에요. 실제 신고 세액은 달라질 수 있어요.";

        assertThat(validator.isValid(explanation, briefing())).isFalse();
    }

    @Test
    void 입력에_없는_날짜를_만든_설명은_거부한다() {
        String explanation = "11월 1일에 다시 확인해 주세요. 실제 신고 세액은 달라질 수 있어요.";

        assertThat(validator.isValid(explanation, briefing())).isFalse();
    }

    @Test
    void 단위가_없는_새로운_숫자나_퍼센트도_거부한다() {
        String explanation = "감면 가능성은 90%예요. 실제 신고 세액은 달라질 수 있어요.";

        assertThat(validator.isValid(explanation, briefing())).isFalse();
    }

    @Test
    void 매수나_인출을_권유하는_설명은_거부한다() {
        assertThat(validator.isValid("절세를 위해 상품을 매수하세요. 실제 신고 세액은 달라질 수 있어요.", briefing()))
                .isFalse();
        assertThat(validator.isValid("지금 원금을 인출하는 것이 유리합니다. 실제 신고 세액은 달라질 수 있어요.", briefing()))
                .isFalse();
    }

    @Test
    void 세액이나_혜택을_보장하는_설명은_거부한다() {
        String explanation = "세제혜택은 무조건 보장됩니다.";

        assertThat(validator.isValid(explanation, briefing())).isFalse();
    }

    @Test
    void 빈_설명은_거부한다() {
        assertThat(validator.isValid(" ", briefing())).isFalse();
        assertThat(validator.isValid(null, briefing())).isFalse();
    }

    @Test
    void 잘못된_금액_표기는_예외_없이_거부한다() {
        String explanation = "현재 예상 감면액은 200,,000원이에요.";

        assertThat(validator.isValid(explanation, briefing())).isFalse();
    }

    private CustomerTaxBriefingResponseDTO briefing() {
        return CustomerTaxBriefingResponseDTO.of(
                1L,
                CustomerTaxBriefingResponseDTO.TaxScenario.of(
                        new BigDecimal("300000"),
                        new BigDecimal("100000"),
                        new BigDecimal("200000")),
                CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.of(
                        new BigDecimal("450000"),
                        new BigDecimal("200000"),
                        new BigDecimal("250000"),
                        new BigDecimal("50000"),
                        LocalDate.of(2026, 10, 30)),
                LocalDateTime.of(2026, 10, 28, 10, 30));
    }
}
