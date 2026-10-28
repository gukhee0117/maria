package com.app.maria.domain.investorassessment.service;

import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.entity.choice.AgeChoice;
import com.app.maria.domain.investorassessment.entity.choice.DerivativeExperienceChoice;
import com.app.maria.domain.investorassessment.entity.choice.ExperienceChoice;
import com.app.maria.domain.investorassessment.entity.choice.FinancialAssetRatioChoice;
import com.app.maria.domain.investorassessment.entity.choice.IncomeChoice;
import com.app.maria.domain.investorassessment.entity.choice.InvestmentPeriodChoice;
import com.app.maria.domain.investorassessment.entity.choice.KnowledgeChoice;
import com.app.maria.domain.investorassessment.entity.choice.LossToleranceChoice;
import com.app.maria.domain.investorassessment.entity.choice.PurposeChoice;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 설문 서비스·API 테스트용 응답. 점수는 각 메서드 주석에 적었다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InvestorAssessmentTestData {

    /** 투자경험 응답. 보낸 순서 그대로 담는다(정렬은 요청 DTO가 한다). */
    public static List<ExperienceChoice> experience(ExperienceChoice... choices) {
        return Arrays.asList(choices);
    }

    /** 합계 50점: 공격투자형. */
    public static InvestorAssessmentSubmitRequestDTO aggressiveAnswers() {
        InvestorAssessmentSubmitRequestDTO a = new InvestorAssessmentSubmitRequestDTO();
        a.setAge(AgeChoice.FROM_30_TO_64);
        a.setPurpose(PurposeChoice.GROW_ASSETS);
        a.setIncome(IncomeChoice.OVER_100M);
        a.setFinancialAssetRatio(FinancialAssetRatioChoice.OVER_50);
        a.setExperience(experience(ExperienceChoice.HIGH_RISK_PRODUCTS));
        a.setKnowledge(KnowledgeChoice.GOOD);
        a.setLossTolerance(LossToleranceChoice.ANY_LOSS);
        a.setInvestmentPeriod(InvestmentPeriodChoice.OVER_3_YEARS);
        a.setDerivativeExperience(DerivativeExperienceChoice.OVER_3_YEARS);
        return a;
    }

    /** 합계 -1점이 0점으로 보정되는 안정형. */
    public static InvestorAssessmentSubmitRequestDTO stableAnswers() {
        InvestorAssessmentSubmitRequestDTO a = new InvestorAssessmentSubmitRequestDTO();
        a.setAge(AgeChoice.UNDER_19);
        a.setPurpose(PurposeChoice.REPAY_DEBT);
        a.setIncome(IncomeChoice.UNDER_30M);
        a.setFinancialAssetRatio(FinancialAssetRatioChoice.UNDER_10);
        a.setExperience(experience(ExperienceChoice.NONE));
        a.setKnowledge(KnowledgeChoice.NONE);
        a.setLossTolerance(LossToleranceChoice.NO_LOSS);
        a.setInvestmentPeriod(InvestmentPeriodChoice.WITHIN_1_YEAR);
        a.setDerivativeExperience(DerivativeExperienceChoice.NONE);
        return a;
    }
}
