package com.app.maria.domain.investorassessment.entity.choice;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 투자성향 설문 문항. answerKey는 제출 요청(InvestorAssessmentSubmitRequestDTO)의 필드 이름과 같아서, 화면이 문항과 응답을 연결하는 데
 * 쓴다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum InvestorQuestion {
    AGE("age", "연령", "나이 구간", false, List.of(AgeChoice.values())),
    PURPOSE("purpose", "투자목적", "내가 투자하는 이유는", false, List.of(PurposeChoice.values())),
    INCOME("income", "재산상황", "내 연 소득은", false, List.of(IncomeChoice.values())),
    FINANCIAL_ASSET_RATIO(
            "financialAssetRatio",
            "재산상황",
            "전체 자산 중 금융 자산의 비중은",
            false,
            List.of(FinancialAssetRatioChoice.values())),
    EXPERIENCE(
            "experience",
            "투자경험",
            "지금까지 투자해본 것을 모두 골라주세요.",
            true,
            List.of(ExperienceChoice.values())),
    KNOWLEDGE(
            "knowledge", "금융지식 및 이해도", "나는 주식, 채권, 펀드를", false, List.of(KnowledgeChoice.values())),
    LOSS_TOLERANCE(
            "lossTolerance",
            "원금보존추구여부 및 손실감내 수준",
            "투자 수익을 위해",
            false,
            List.of(LossToleranceChoice.values())),
    INVESTMENT_PERIOD(
            "investmentPeriod",
            "투자예정기간",
            "나는 투자를",
            false,
            List.of(InvestmentPeriodChoice.values())),
    DERIVATIVE_EXPERIENCE(
            "derivativeExperience",
            "적정성원칙 대상상품의 투자경험",
            "파생상품 투자 경험은",
            false,
            List.of(DerivativeExperienceChoice.values()));

    private final String answerKey;
    private final String category;
    private final String text;
    private final boolean multiple;
    private final List<? extends ScoredChoice> choices;
}
