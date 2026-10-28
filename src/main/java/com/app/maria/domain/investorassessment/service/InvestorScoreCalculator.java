package com.app.maria.domain.investorassessment.service;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.entity.choice.ExperienceChoice;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 설문 응답으로 점수를 계산하고 투자성향으로 바꾼다. 음수 합계는 0으로 보정한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InvestorScoreCalculator {

    public static int totalScore(InvestorAssessmentSubmitRequestDTO answers) {
        // 투자경험은 복수 선택이다. 합산 방식이 정해져 있지 않아 가장 높은 점수 하나만 쓴다.
        int experienceScore =
                answers.getExperience().stream()
                        .mapToInt(ExperienceChoice::score)
                        .max()
                        .orElseThrow();
        int sum =
                answers.getAge().score()
                        + answers.getPurpose().score()
                        + answers.getIncome().score()
                        + answers.getFinancialAssetRatio().score()
                        + experienceScore
                        + answers.getKnowledge().score()
                        + answers.getLossTolerance().score()
                        + answers.getInvestmentPeriod().score()
                        + answers.getDerivativeExperience().score();
        return Math.max(0, sum);
    }

    public static InvestorType toType(int totalScore) {
        if (totalScore <= 10) {
            return InvestorType.STABLE;
        }
        if (totalScore <= 20) {
            return InvestorType.CONSERVATIVE;
        }
        if (totalScore <= 30) {
            return InvestorType.NEUTRAL;
        }
        if (totalScore <= 40) {
            return InvestorType.ACTIVE;
        }
        return InvestorType.AGGRESSIVE;
    }
}
