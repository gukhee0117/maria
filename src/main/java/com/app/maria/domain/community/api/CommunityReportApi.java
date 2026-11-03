package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.request.ReportRequestDTO;
import com.app.maria.domain.community.service.CommunityReportService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 글·댓글 신고. 신고자는 로그인한 본인이고 요청에서 고객 ID를 받지 않는다. */
@RestController
@RequestMapping("/api/customer/community/reports")
@RequiredArgsConstructor
public class CommunityReportApi {
    private final CommunityReportService reportService;

    @PostMapping
    @Operation(summary = "글 또는 댓글 신고 (CSRF 필수). 같은 대상은 한 번만, 5건이 쌓이면 자동으로 숨긴다")
    public ApiResponseDTO<Void> report(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody ReportRequestDTO request) {
        reportService.report(principal.customerId(), request);
        return ApiResponseDTO.of("신고 접수 완료");
    }
}
