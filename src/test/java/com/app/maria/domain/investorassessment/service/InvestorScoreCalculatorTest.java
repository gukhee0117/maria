package com.app.maria.domain.investorassessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.customer.entity.InvestorType;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvestorScoreCalculatorTest {

    /** 문항마다 가장 높은 배점의 보기. 합계 50점. */
    private static InvestorAssessmentSubmitRequestDTO maxAnswers() {
        InvestorAssessmentSubmitRequestDTO a = new InvestorAssessmentSubmitRequestDTO();
        a.setAge(AgeChoice.FROM_30_TO_64);
        a.setPurpose(PurposeChoice.GROW_ASSETS);
        a.setIncome(IncomeChoice.OVER_100M);
        a.setFinancialAssetRatio(FinancialAssetRatioChoice.OVER_50);
        a.setExperience(InvestorAssessmentTestData.experience(ExperienceChoice.HIGH_RISK_PRODUCTS));
        a.setKnowledge(KnowledgeChoice.GOOD);
        a.setLossTolerance(LossToleranceChoice.ANY_LOSS);
        a.setInvestmentPeriod(InvestmentPeriodChoice.OVER_3_YEARS);
        a.setDerivativeExperience(DerivativeExperienceChoice.OVER_3_YEARS);
        return a;
    }

    /** 문항마다 가장 낮은 배점의 보기. 합계 1-5+1+1+0+0+0+1+0 = -1점. */
    private static InvestorAssessmentSubmitRequestDTO minAnswers() {
        InvestorAssessmentSubmitRequestDTO a = new InvestorAssessmentSubmitRequestDTO();
        a.setAge(AgeChoice.UNDER_19);
        a.setPurpose(PurposeChoice.REPAY_DEBT);
        a.setIncome(IncomeChoice.UNDER_30M);
        a.setFinancialAssetRatio(FinancialAssetRatioChoice.UNDER_10);
        a.setExperience(InvestorAssessmentTestData.experience(ExperienceChoice.NONE));
        a.setKnowledge(KnowledgeChoice.NONE);
        a.setLossTolerance(LossToleranceChoice.NO_LOSS);
        a.setInvestmentPeriod(InvestmentPeriodChoice.WITHIN_1_YEAR);
        a.setDerivativeExperience(DerivativeExperienceChoice.NONE);
        return a;
    }

    @Test
    @DisplayName("모든 문항 최고 배점은 50점이다")
    void maxIs50() {
        assertThat(InvestorScoreCalculator.totalScore(maxAnswers())).isEqualTo(50);
    }

    @Test
    @DisplayName("합계가 음수면 0으로 보정한다")
    void clampsNegativeToZero() {
        assertThat(InvestorScoreCalculator.totalScore(minAnswers())).isZero();
    }

    @Test
    @DisplayName("투자경험을 복수 선택하면 가장 높은 점수 하나만 쓴다")
    void experienceUsesHighestOnly() {
        InvestorAssessmentSubmitRequestDTO a = minAnswers();
        a.setExperience(
                InvestorAssessmentTestData.experience(
                        ExperienceChoice.DEPOSITS,
                        ExperienceChoice.STOCKS_AND_EQUITY_FUNDS,
                        ExperienceChoice.ELB_AND_BOND_FUNDS));

        // 최저 응답 -1점에서 투자경험만 0점 → 8점으로 바뀌므로 7점. 2+8을 더하면 9점이 된다.
        assertThat(InvestorScoreCalculator.totalScore(a)).isEqualTo(7);
    }

    @Test
    @DisplayName("점수 경계값으로 성향이 갈린다")
    void typeBoundaries() {
        assertThat(InvestorScoreCalculator.toType(0)).isEqualTo(InvestorType.STABLE);
        assertThat(InvestorScoreCalculator.toType(10)).isEqualTo(InvestorType.STABLE);
        assertThat(InvestorScoreCalculator.toType(11)).isEqualTo(InvestorType.CONSERVATIVE);
        assertThat(InvestorScoreCalculator.toType(20)).isEqualTo(InvestorType.CONSERVATIVE);
        assertThat(InvestorScoreCalculator.toType(21)).isEqualTo(InvestorType.NEUTRAL);
        assertThat(InvestorScoreCalculator.toType(30)).isEqualTo(InvestorType.NEUTRAL);
        assertThat(InvestorScoreCalculator.toType(31)).isEqualTo(InvestorType.ACTIVE);
        assertThat(InvestorScoreCalculator.toType(40)).isEqualTo(InvestorType.ACTIVE);
        assertThat(InvestorScoreCalculator.toType(41)).isEqualTo(InvestorType.AGGRESSIVE);
        assertThat(InvestorScoreCalculator.toType(50)).isEqualTo(InvestorType.AGGRESSIVE);
    }
}
