package com.app.maria.domain.investorassessment.dto.response;

import com.app.maria.domain.customer.entity.InvestorType;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 고객의 현재 투자성향 상태. 만료를 막을지는 이 값을 쓰는 각 기능이 정한다. */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class InvestorAssessmentStatusResponseDTO {
    private final InvestorType investorType;

    /** 설문을 마친 고객인가. 미진단(UNASSIGNED)이면 false. */
    private final boolean assessed;

    /** 진단 기록이 없는 고객(시드 고객)은 null. */
    private final LocalDateTime assessedAt;

    private final LocalDateTime expiresAt;
    private final boolean expired;

    public static InvestorAssessmentStatusResponseDTO unassigned() {
        return new InvestorAssessmentStatusResponseDTO(
                InvestorType.UNASSIGNED, false, null, null, false);
    }

    /** 성향 값은 있지만 진단 기록이 없는 고객. 진단 시각을 모르므로 만료로 보지 않는다. */
    public static InvestorAssessmentStatusResponseDTO withoutRecord(InvestorType investorType) {
        return new InvestorAssessmentStatusResponseDTO(investorType, true, null, null, false);
    }

    public static InvestorAssessmentStatusResponseDTO of(
            InvestorType investorType,
            LocalDateTime assessedAt,
            LocalDateTime expiresAt,
            boolean expired) {
        return new InvestorAssessmentStatusResponseDTO(
                investorType, true, assessedAt, expiresAt, expired);
    }
}
