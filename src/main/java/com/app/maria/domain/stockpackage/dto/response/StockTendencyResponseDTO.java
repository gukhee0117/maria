package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO.ExplanationBlockResponse;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/** 설문 투자성향. */
@Getter
public class StockTendencyResponseDTO {
    private final SurveyResponse survey;
    private final List<ExplanationBlockResponse> explanation;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public StockTendencyResponseDTO(StockTendencyVO vo) {
        this.survey = new SurveyResponse(vo.getSurvey());
        this.explanation = vo.getExplanation().stream().map(ExplanationBlockResponse::new).toList();
        this.disclaimer = vo.getDisclaimer();
        this.checkedAt = vo.getCheckedAt();
    }

    @Getter
    public static class SurveyResponse {
        private final boolean available;
        private final String investorType;
        private final String label;
        private final Integer allowedMinGrade;
        private final LocalDateTime assessedAt;
        private final boolean expired;

        public SurveyResponse(StockTendencyVO.Survey vo) {
            this.available = vo.available();
            this.investorType = vo.investorType();
            this.label = vo.label();
            this.allowedMinGrade = vo.allowedMinGrade();
            this.assessedAt = vo.assessedAt();
            this.expired = vo.expired();
        }
    }
}
