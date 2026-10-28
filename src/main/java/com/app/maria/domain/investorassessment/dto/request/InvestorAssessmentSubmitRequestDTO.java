package com.app.maria.domain.investorassessment.dto.request;

import com.app.maria.domain.investorassessment.entity.choice.AgeChoice;
import com.app.maria.domain.investorassessment.entity.choice.DerivativeExperienceChoice;
import com.app.maria.domain.investorassessment.entity.choice.ExperienceChoice;
import com.app.maria.domain.investorassessment.entity.choice.FinancialAssetRatioChoice;
import com.app.maria.domain.investorassessment.entity.choice.IncomeChoice;
import com.app.maria.domain.investorassessment.entity.choice.InvestmentPeriodChoice;
import com.app.maria.domain.investorassessment.entity.choice.KnowledgeChoice;
import com.app.maria.domain.investorassessment.entity.choice.LossToleranceChoice;
import com.app.maria.domain.investorassessment.entity.choice.PurposeChoice;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 투자성향 설문 제출. 문항마다 선택한 보기를 보내고, 점수는 서버가 계산한다. */
@Getter
@Setter
public class InvestorAssessmentSubmitRequestDTO {
    @NotNull(message = "나이 구간을 선택해 주세요.")
    private AgeChoice age;

    @NotNull(message = "투자 목적을 선택해 주세요.")
    private PurposeChoice purpose;

    @NotNull(message = "연 소득을 선택해 주세요.")
    private IncomeChoice income;

    @NotNull(message = "금융자산 비중을 선택해 주세요.")
    private FinancialAssetRatioChoice financialAssetRatio;

    @NotEmpty(message = "투자 경험을 하나 이상 선택해 주세요.")
    private List<@NotNull ExperienceChoice> experience;

    @NotNull(message = "금융 지식 수준을 선택해 주세요.")
    private KnowledgeChoice knowledge;

    @NotNull(message = "감수할 수 있는 손실을 선택해 주세요.")
    private LossToleranceChoice lossTolerance;

    @NotNull(message = "투자 예정 기간을 선택해 주세요.")
    private InvestmentPeriodChoice investmentPeriod;

    @NotNull(message = "파생상품 투자 경험을 선택해 주세요.")
    private DerivativeExperienceChoice derivativeExperience;

    /** 저장과 점수 계산은 enum 순서로 정렬한 값을 쓴다. 같은 응답이면 같은 JSON이 된다. */
    public List<ExperienceChoice> getExperience() {
        return experience == null ? null : experience.stream().sorted().toList();
    }

    /** 같은 보기를 두 번 보낸 요청은 거절한다. 검증은 보낸 그대로(정렬·중복 제거 전)의 값으로 한다. */
    @JsonIgnore
    @AssertTrue(message = "투자 경험에 같은 보기를 두 번 선택할 수 없습니다.")
    public boolean isExperienceUnique() {
        return experience == null || experience.stream().distinct().count() == experience.size();
    }

    /** "투자 경험이 없어요"는 다른 보기와 함께 고를 수 없다. 응답 JSON에 속성으로 들어가지 않게 무시한다. */
    @JsonIgnore
    @AssertTrue(message = "'투자 경험이 없어요'는 다른 보기와 함께 선택할 수 없습니다.")
    public boolean isExperienceConsistent() {
        return experience == null
                || !experience.contains(ExperienceChoice.NONE)
                || experience.size() == 1;
    }
}
