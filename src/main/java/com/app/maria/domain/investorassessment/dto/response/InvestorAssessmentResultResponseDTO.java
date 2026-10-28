package com.app.maria.domain.investorassessment.dto.response;

import com.app.maria.domain.customer.entity.InvestorType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 설문 제출 결과. */
@Getter
@RequiredArgsConstructor
public class InvestorAssessmentResultResponseDTO {
    private final int totalScore;
    private final InvestorType investorType;
    private final LocalDateTime assessedAt;

    /** 진단 시각 + 24개월. 이 시각부터 만료다. */
    private final LocalDateTime expiresAt;
}
