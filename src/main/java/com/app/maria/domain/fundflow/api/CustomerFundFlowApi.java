package com.app.maria.domain.fundflow.api;

import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowTransactionsResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowWithdrawalScheduleResponseDTO;
import com.app.maria.domain.fundflow.service.CustomerFundFlowService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/fund-flow")
public class CustomerFundFlowApi {

    private final CustomerFundFlowService customerFundFlowService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponseDTO<FundFlowCustomerSummaryResponseDTO>> getSummary(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "내 자금 흐름 요약 조회 성공",
                        customerFundFlowService.getSummary(principal.customerId())));
    }

    @GetMapping("/monthly")
    public ResponseEntity<ApiResponseDTO<FundFlowMonthlyResponseDTO>> getMonthly(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "내 월별 자금 흐름 조회 성공",
                        customerFundFlowService.getMonthly(principal.customerId())));
    }

    @GetMapping("/allocation")
    public ResponseEntity<ApiResponseDTO<FundFlowCustomerAllocationResponseDTO>> getAllocation(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "내 운용처 조회 성공",
                        customerFundFlowService.getAllocation(principal.customerId())));
    }

    @GetMapping("/relief")
    public ResponseEntity<ApiResponseDTO<FundFlowCustomerReliefResponseDTO>> getRelief(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "내 예상 세금 혜택 조회 성공",
                        customerFundFlowService.getRelief(principal.customerId())));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponseDTO<FundFlowTransactionsResponseDTO>> getTransactions(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "내 자금 이동 내역 조회 성공",
                        customerFundFlowService.getTransactions(principal.customerId())));
    }

    @GetMapping("/withdrawal-schedule")
    public ResponseEntity<ApiResponseDTO<FundFlowWithdrawalScheduleResponseDTO>>
            getWithdrawalSchedule(
                    @AuthenticationPrincipal(errorOnInvalidType = true)
                            CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "내 인출 가능 일정 조회 성공",
                        customerFundFlowService.getWithdrawalSchedule(principal.customerId())));
    }
}
