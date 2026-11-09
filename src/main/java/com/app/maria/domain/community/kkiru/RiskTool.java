package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.risk.dto.RiskGradeClassificationDTO;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 위험등급과 가격 기반 지표를 분리해 설명한다. 주식 등급은 현재 종목 상태로 확인하며, VaR·변동성은 유효한 가격 이력이 있을 때만 제공한다. */
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
        var classification = riskGradeService.findClassificationByTicker(question.ticker());
        if (classification.isEmpty()) return List.of();
        var current = classification.get();
        Optional<UsableRiskGradeDTO> metrics;
        try {
            metrics = riskGradeService.findUsableByTicker(question.ticker());
        } catch (AppException e) {
            if (!e.getErrorType().name().startsWith("RISK_")) throw e;
            metrics = Optional.empty();
        }
        String text =
                metrics.map(g -> sentence(g, current))
                        .orElse(
                                current.name()
                                        + "의 위험등급은 "
                                        + current.riskGrade()
                                        + "등급입니다(1등급 매우 높은 위험, 6등급 매우 낮은 위험). 유효한 가격 지표가 없어 VaR와 변동성은 제공하지 않습니다.");
        if ("STOCK_RULE_CURRENT_STATUS".equals(current.basis()))
            text += " 주식 등급은 상품 분류와 현재 종목 상태 기준이며 VaR·변동성과 별도로 산정됩니다.";
        return List.of(new Evidence("risk:" + current.ticker(), text, null));
    }

    private static String sentence(
            UsableRiskGradeDTO grade, RiskGradeClassificationDTO classification) {
        StringBuilder text =
                new StringBuilder()
                        .append(classification.name())
                        .append("의 위험등급은 ")
                        .append(classification.riskGrade())
                        .append("등급입니다(1등급 매우 높은 위험, 6등급 매우 낮은 위험). 관측 기간의 일간수익률 기준 97.5% VaR은 ")
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
