package com.app.maria.domain.fundflow.api;

import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowPeriodRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowConcentrationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowEarlyWithdrawalResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMaturityResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowRetentionResponseDTO;
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
    public ResponseEntity<ApiResponseDTO<FundFlowFunnelResponseDTO>> getFunnel(
            FundFlowPeriodRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("가입→첫 매수 퍼널 조회 성공", adminFundFlowService.getFunnel(request)));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponseDTO<FundFlowOverviewResponseDTO>> getOverview(
            FundFlowPeriodRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("자금 환류 개요 조회 성공", adminFundFlowService.getOverview(request)));
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
    public ResponseEntity<ApiResponseDTO<FundFlowSegmentResponseDTO>> getSegments(
            FundFlowPeriodRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("세그먼트별 재투자율 조회 성공", adminFundFlowService.getSegments(request)));
    }

    @GetMapping("/relief")
    public ResponseEntity<ApiResponseDTO<FundFlowReliefResponseDTO>> getRelief(
            FundFlowReliefRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("감면액 조회 성공", adminFundFlowService.getRelief(request)));
    }

    @GetMapping("/allocation")
    public ResponseEntity<ApiResponseDTO<FundFlowAllocationResponseDTO>> getAllocation(
            FundFlowPeriodRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("국내 운용처 조회 성공", adminFundFlowService.getAllocation(request)));
    }

    @GetMapping("/concentration")
    public ResponseEntity<ApiResponseDTO<FundFlowConcentrationResponseDTO>> getConcentration() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("혜택 집중도 조회 성공", adminFundFlowService.getConcentration()));
    }

    @GetMapping("/retention")
    public ResponseEntity<ApiResponseDTO<FundFlowRetentionResponseDTO>> getRetention(
            FundFlowPeriodRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("원금 유지 조회 성공", adminFundFlowService.getRetention(request)));
    }

    @GetMapping("/maturity")
    public ResponseEntity<ApiResponseDTO<FundFlowMaturityResponseDTO>> getMaturity() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("만기 도래 스케줄 조회 성공", adminFundFlowService.getMaturity()));
    }

    @GetMapping("/early-withdrawal")
    public ResponseEntity<ApiResponseDTO<FundFlowEarlyWithdrawalResponseDTO>> getEarlyWithdrawal() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("1년 미경과 원금 인출 조회 성공", adminFundFlowService.getEarlyWithdrawal()));
    }
}
