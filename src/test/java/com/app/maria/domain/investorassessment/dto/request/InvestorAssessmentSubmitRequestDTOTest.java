package com.app.maria.domain.investorassessment.dto.request;

import static com.app.maria.domain.investorassessment.service.InvestorAssessmentTestData.experience;
import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.investorassessment.entity.choice.AgeChoice;
import com.app.maria.domain.investorassessment.entity.choice.DerivativeExperienceChoice;
import com.app.maria.domain.investorassessment.entity.choice.ExperienceChoice;
import com.app.maria.domain.investorassessment.entity.choice.FinancialAssetRatioChoice;
import com.app.maria.domain.investorassessment.entity.choice.IncomeChoice;
import com.app.maria.domain.investorassessment.entity.choice.InvestmentPeriodChoice;
import com.app.maria.domain.investorassessment.entity.choice.KnowledgeChoice;
import com.app.maria.domain.investorassessment.entity.choice.LossToleranceChoice;
import com.app.maria.domain.investorassessment.entity.choice.PurposeChoice;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvestorAssessmentSubmitRequestDTOTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static InvestorAssessmentSubmitRequestDTO validRequest() {
        InvestorAssessmentSubmitRequestDTO a = new InvestorAssessmentSubmitRequestDTO();
        a.setAge(AgeChoice.FROM_30_TO_64);
        a.setPurpose(PurposeChoice.GROW_ASSETS);
        a.setIncome(IncomeChoice.FROM_50M_TO_70M);
        a.setFinancialAssetRatio(FinancialAssetRatioChoice.FROM_10_TO_30);
        a.setExperience(experience(ExperienceChoice.DEPOSITS));
        a.setKnowledge(KnowledgeChoice.FAIR);
        a.setLossTolerance(LossToleranceChoice.UP_TO_20);
        a.setInvestmentPeriod(InvestmentPeriodChoice.WITHIN_3_YEARS);
        a.setDerivativeExperience(DerivativeExperienceChoice.NONE);
        return a;
    }

    @Test
    @DisplayName("모든 문항에 답하면 검증을 통과한다")
    void validRequestPasses() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @Test
    @DisplayName("문항 하나를 비우면 검증에 걸린다")
    void missingAnswerFails() {
        InvestorAssessmentSubmitRequestDTO a = validRequest();
        a.setDerivativeExperience(null);

        assertThat(validator.validate(a)).hasSize(1);
    }

    @Test
    @DisplayName("투자경험을 하나도 고르지 않으면 검증에 걸린다")
    void emptyExperienceFails() {
        InvestorAssessmentSubmitRequestDTO a = validRequest();
        a.setExperience(experience());

        assertThat(validator.validate(a)).hasSize(1);
    }
}
