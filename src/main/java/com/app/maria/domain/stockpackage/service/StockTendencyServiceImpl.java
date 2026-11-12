package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Survey;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockTendencyServiceImpl implements StockTendencyService {
    private final InvestorAssessmentService investorAssessmentService;
    private final BusinessClockService businessClockService;

    @Override
    public StockTendencyResponseDTO getTendency(Long customerId) {
        LocalDateTime now = businessClockService.now();
        InvestorAssessmentStatusResponseDTO status = investorAssessmentService.getMine(customerId);
        Optional<InvestorRiskRange> range =
                status.isAssessed() && !status.isExpired()
                        ? InvestorRiskRange.of(status.getInvestorType())
                        : Optional.empty();
        Survey survey =
                new Survey(
                        range.isPresent(),
                        range.isPresent() ? status.getInvestorType().name() : null,
                        range.map(InvestorRiskRange::label).orElse(null),
                        range.map(InvestorRiskRange::minGrade).orElse(null),
                        status.getAssessedAt(),
                        status.isExpired());
        return new StockTendencyResponseDTO(
                StockTendencyVO.builder()
                        .survey(survey)
                        .explanation(StockTendencyExplanationBuilder.build(survey))
                        .disclaimer(StockPackageCheckServiceImpl.DISCLAIMER)
                        .checkedAt(now)
                        .build());
    }
}
