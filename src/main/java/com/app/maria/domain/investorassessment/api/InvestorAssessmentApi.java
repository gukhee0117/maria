package com.app.maria.domain.investorassessment.api;

import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentResultResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorQuestionResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 고객 투자성향 설문. 고객은 로그인한 본인뿐이고 요청에서 고객 ID를 받지 않는다. */
@RestController
@RequestMapping("/api/customer/investor-assessment")
@RequiredArgsConstructor
public class InvestorAssessmentApi {
    private final InvestorAssessmentService investorAssessmentService;

    @GetMapping("/questions")
    @Operation(summary = "투자성향 설문 문항과 보기 조회")
    public ApiResponseDTO<List<InvestorQuestionResponseDTO>> questions() {
        return ApiResponseDTO.of("설문 문항 조회 성공", investorAssessmentService.questions());
    }

    @PostMapping
    @Operation(summary = "투자성향 설문 제출 (CSRF 필수). 점수를 계산해 진단 기록을 남기고 현재 성향을 바꾼다. 재진단도 같은 API")
    public ApiResponseDTO<InvestorAssessmentResultResponseDTO> submit(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody InvestorAssessmentSubmitRequestDTO request) {
        return ApiResponseDTO.of(
                "투자성향 진단 완료", investorAssessmentService.submit(principal.customerId(), request));
    }

    @GetMapping("/me")
    @Operation(summary = "내 투자성향 상태 조회 (성향, 진단 시각, 만료 여부)")
    public ApiResponseDTO<InvestorAssessmentStatusResponseDTO> me(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of(
                "투자성향 조회 성공", investorAssessmentService.getMine(principal.customerId()));
    }
}
