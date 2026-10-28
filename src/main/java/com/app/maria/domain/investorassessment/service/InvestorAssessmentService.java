package com.app.maria.domain.investorassessment.service;

import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentResultResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorQuestionResponseDTO;
import java.util.List;

public interface InvestorAssessmentService {
    /** 설문 문항과 보기. */
    List<InvestorQuestionResponseDTO> questions();

    /** 설문을 제출한다. 점수 계산, 진단 기록 저장, 고객 현재 성향 변경을 한 트랜잭션으로 처리한다. 재진단도 같다. */
    InvestorAssessmentResultResponseDTO submit(
            Long customerId, InvestorAssessmentSubmitRequestDTO request);

    /** 고객의 현재 투자성향 상태. */
    InvestorAssessmentStatusResponseDTO getMine(Long customerId);
}
