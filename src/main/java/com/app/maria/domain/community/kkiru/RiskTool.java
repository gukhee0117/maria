package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 위험등급 도구(설계 4.4 ①). 팀의 Python risk가 계산해 둔 {@code stock_risk_grade}를 읽는다. 등급은 1이 매우 높은 위험, 6이 매우 낮은
 * 위험이다(숫자가 클수록 위험하다고 쓰면 틀린다). 자료가 없거나(해외·이력 3년 미만) 만료됐으면 빈 결과다.
 */
@Component
@Order(60)
@RequiredArgsConstructor
public class RiskTool implements EvidenceTool {
    private static final List<String> WORDS = List.of("위험", "등급", "변동성", "안전", "흔들");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    private final RiskGradeService riskGradeService;

    @Override
    public String name() {
        return "risk";
    }

    @Override
    public boolean isRelevant(KkiruQuestion question) {
        return QuestionWords.containsAny(question, WORDS);
    }

    @Override
    public List<Evidence> collect(KkiruQuestion question) {
        return riskGradeService
                .findUsableByTicker(question.ticker())
                .map(
                        grade ->
                                List.of(
                                        new Evidence(
                                                "risk:" + grade.ticker(), sentence(grade), null)))
                .orElse(List.of());
    }

    private static String sentence(UsableRiskGradeDTO grade) {
        StringBuilder text =
                new StringBuilder()
                        .append(grade.name())
                        .append("의 위험등급은 ")
                        .append(grade.riskGrade())
                        .append("등급입니다(1등급 매우 높은 위험, 6등급 매우 낮은 위험). 3년 일간수익률 기준 97.5% VaR은 ")
                        .append(percent(grade.var975()))
                        .append(", 연 변동성은 ")
                        .append(percent(grade.volatility()))
                        .append("이며 기준 기간은 ")
                        .append(date(grade.priceFrom()))
                        .append("부터 ")
                        .append(date(grade.priceTo()))
                        .append("까지입니다(자체 산정).");
        if (grade.status() == RiskDataStatus.STALE) {
            text.append(" 기준 시점이 오래되었습니다.");
        }
        return text.toString();
    }

    /** 0.401 → 40.1%. */
    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String date(LocalDate date) {
        return date.format(DATE);
    }
}
