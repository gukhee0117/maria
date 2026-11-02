package com.app.maria.domain.fundflow.api;

import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;
import com.app.maria.domain.fundflow.service.AdminFundFlowService;
import com.app.maria.global.response.ApiResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/fund-flow")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class AdminFundFlowApi {

    private final AdminFundFlowService adminFundFlowService;

    @GetMapping("/funnel")
    public ResponseEntity<ApiResponseDTO<FundFlowFunnelResponseDTO>> getFunnel() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("가입→첫 매수 퍼널 조회 성공", adminFundFlowService.getFunnel()));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponseDTO<FundFlowOverviewResponseDTO>> getOverview() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("자금 환류 개요 조회 성공", adminFundFlowService.getOverview()));
    }

    @GetMapping("/leakage")
    public ResponseEntity<ApiResponseDTO<FundFlowLeakageResponseDTO>> getLeakage() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("혜택 누수 분류 조회 성공", adminFundFlowService.getLeakage()));
    }

    @GetMapping("/monthly")
    public ResponseEntity<ApiResponseDTO<FundFlowMonthlyResponseDTO>> getMonthly(
            FundFlowMonthlyRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("월별 자금 흐름 조회 성공", adminFundFlowService.getMonthly(request)));
    }

    @GetMapping("/segments")
    public ResponseEntity<ApiResponseDTO<FundFlowSegmentResponseDTO>> getSegments() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("세그먼트별 재투자율 조회 성공", adminFundFlowService.getSegments()));
    }

    @GetMapping("/relief")
    public ResponseEntity<ApiResponseDTO<FundFlowReliefResponseDTO>> getRelief(
            FundFlowReliefRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("감면액 조회 성공", adminFundFlowService.getRelief(request)));
    }

    @GetMapping("/allocation")
    public ResponseEntity<ApiResponseDTO<FundFlowAllocationResponseDTO>> getAllocation() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("국내 운용처 조회 성공", adminFundFlowService.getAllocation()));
    }
}
