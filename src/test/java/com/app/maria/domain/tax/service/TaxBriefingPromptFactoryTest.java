package com.app.maria.domain.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaxBriefingPromptFactoryTest {

    private final TaxBriefingPromptFactory promptFactory = new TaxBriefingPromptFactory();

    @Test
    void 계산된_세액과_확정일을_LLM_입력으로_만든다() {
        CustomerTaxBriefingResponseDTO briefing =
                CustomerTaxBriefingResponseDTO.of(
                        77L,
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

        String prompt = promptFactory.create(briefing);

        assertThat(prompt)
                .isEqualTo(
                        """
                        아래 절세 계산 결과를 고객이 이해하기 쉽게 설명해.
                        현재 일반 세액: 300000원
                        현재 RIA 적용 세액: 100000원
                        현재 예상 감면액: 200000원
                        정산 반영 후 예상 일반 세액: 450000원
                        정산 반영 후 예상 RIA 세액: 200000원
                        정산 반영 후 예상 감면액: 250000원
                        정산 반영 시 추가 감면 예상액: 50000원
                        예상 확정일: 2026-10-30""");
        assertThat(prompt).doesNotContain("77", "2026-10-28T10:30");
    }

    @Test
    void 값이_없는_선택항목은_LLM_입력에서_제외한다() {
        CustomerTaxBriefingResponseDTO briefing =
                CustomerTaxBriefingResponseDTO.of(
                        1L,
                        CustomerTaxBriefingResponseDTO.TaxScenario.of(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.of(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null),
                        LocalDateTime.of(2026, 10, 28, 10, 30));

        String prompt = promptFactory.create(briefing);

        assertThat(prompt).doesNotContain("추가 감면 예상액", "예상 확정일");
    }

    @Test
    void 계산_결과가_없으면_입력문을_만들지_않는다() {
        assertThatNullPointerException()
                .isThrownBy(() -> promptFactory.create(null))
                .withMessage("절세 브리핑 계산 결과가 필요합니다.");
    }
}
