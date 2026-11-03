package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.request.ReportActionRequestDTO;
import com.app.maria.domain.community.dto.response.AdminReportProcessResponseDTO;
import com.app.maria.domain.community.dto.response.AdminReportResponseDTO;
import com.app.maria.domain.community.service.CommunityReportAdminService;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.global.response.ApiResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 신고 목록과 처리. 목록은 모든 관리자 역할이 볼 수 있고, 처리는 ADMIN·REVIEWER만 한다. */
@RestController
@RequestMapping("/api/admin/community/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'VIEWER')")
@RequiredArgsConstructor
public class CommunityReportAdminApi {
    private final CommunityReportAdminService adminService;

    @GetMapping
    @Operation(summary = "신고 목록(최신순). 기본은 처리 대기")
    public ApiResponseDTO<List<AdminReportResponseDTO>> list(
            @RequestParam(defaultValue = "PENDING") ReportStatus status,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
        return ApiResponseDTO.of("신고 목록 조회 성공", adminService.list(status, size));
    }

    @PutMapping("/{reportId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "신고 처리: HIDE·DELETE는 인정, RESTORE는 기각하고 되살림. 같은 대상의 대기 신고를 모두 닫는다")
    public ApiResponseDTO<AdminReportProcessResponseDTO> process(
            @PathVariable @Positive Long reportId,
            @Valid @RequestBody ReportActionRequestDTO request) {
        return ApiResponseDTO.of("신고 처리 완료", adminService.process(reportId, request.action()));
    }
}
