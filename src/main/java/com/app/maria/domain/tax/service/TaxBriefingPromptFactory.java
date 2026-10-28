package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TaxBriefingPromptFactory {

    public String create(CustomerTaxBriefingResponseDTO briefing) {
        Objects.requireNonNull(briefing, "절세 브리핑 계산 결과가 필요합니다.");
        Objects.requireNonNull(briefing.getCurrent(), "현재 세액 계산 결과가 필요합니다.");
        Objects.requireNonNull(briefing.getExpected(), "예상 세액 계산 결과가 필요합니다.");

        StringBuilder prompt = new StringBuilder("아래 절세 계산 결과를 고객이 이해하기 쉽게 설명해.\n");

        appendAmount(prompt, "현재 일반 세액", briefing.getCurrent().getGeneralTax());
        appendAmount(prompt, "현재 RIA 적용 세액", briefing.getCurrent().getRiaTax());
        appendAmount(prompt, "현재 예상 감면액", briefing.getCurrent().getReliefAmount());
        appendAmount(prompt, "정산 반영 후 예상 일반 세액", briefing.getExpected().getGeneralTax());
        appendAmount(prompt, "정산 반영 후 예상 RIA 세액", briefing.getExpected().getRiaTax());
        appendAmount(prompt, "정산 반영 후 예상 감면액", briefing.getExpected().getReliefAmount());
        appendAmount(
                prompt, "정산 반영 시 추가 감면 예상액", briefing.getExpected().getAdditionalReliefAmount());
        appendDate(prompt, "예상 확정일", briefing.getExpected().getExpectedFinalAt());

        return prompt.toString().stripTrailing();
    }

    private void appendAmount(StringBuilder prompt, String label, BigDecimal amount) {
        if (amount != null) {
            prompt.append(label).append(": ").append(amount.toPlainString()).append("원\n");
        }
    }

    private void appendDate(StringBuilder prompt, String label, LocalDate date) {
        if (date != null) {
            prompt.append(label).append(": ").append(date).append('\n');
        }
    }
}
