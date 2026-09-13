package com.app.maria.domain.accountclosure.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.accountclosure.dto.request.AccountClosureApplyRequestDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureDetailResponseDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureResponseDTO;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotAllowedException;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotFoundException;
import com.app.maria.domain.accountclosure.exception.AccountClosureProcessingException;
import com.app.maria.domain.accountclosure.exception.AccountClosureStateConflictException;
import com.app.maria.domain.accountclosure.service.AccountClosureService;
import com.app.maria.domain.accountclosure.type.AccountClosureStatus;
import com.app.maria.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AccountClosureApiTest {

    @Mock private AccountClosureService accountClosureService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new AccountClosureApi(accountClosureService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(
                                        Jackson2ObjectMapperBuilder.json()
                                                .featuresToDisable(
                                                        SerializationFeature
                                                                .WRITE_DATES_AS_TIMESTAMPS)
                                                .build()))
                        .build();

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                7L, null, List.of(new SimpleGrantedAuthority("ROLE_REVIEWER"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validRequestReturnsCreatedClosureRequestId() throws Exception {
        when(accountClosureService.applyClosure(eq(10L), any(AccountClosureApplyRequestDTO.class)))
                .thenReturn(30L);

        mockMvc.perform(
                        post("/api/admin/account-closures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 완료"))
                .andExpect(jsonPath("$.data").value(30));

        ArgumentCaptor<AccountClosureApplyRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(AccountClosureApplyRequestDTO.class);
        verify(accountClosureService).applyClosure(eq(10L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getDestinationGeneralAccountId()).isEqualTo(20L);
        assertThat(requestCaptor.getValue().isEarlyWithdrawalAgreed()).isTrue();
    }

    @Test
    void missingCustomerIdReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account-closures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "destinationGeneralAccountId": 20,
                                          "earlyWithdrawalAgreed": true
                                        }
                                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountClosureService);
    }

    @Test
    void nonPositiveDestinationAccountIdReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account-closures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "customerId": 10,
                                          "destinationGeneralAccountId": 0,
                                          "earlyWithdrawalAgreed": false
                                        }
                                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountClosureService);
    }

    @Test
    void notAllowedClosureReturnsBadRequest() throws Exception {
        when(accountClosureService.applyClosure(eq(10L), any(AccountClosureApplyRequestDTO.class)))
                .thenThrow(new AccountClosureNotAllowedException("해지를 신청할 수 없습니다."));

        mockMvc.perform(
                        post("/api/admin/account-closures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("해지를 신청할 수 없습니다."));
    }

    @Test
    void closureProcessingFailureReturnsInternalServerError() throws Exception {
        when(accountClosureService.applyClosure(eq(10L), any(AccountClosureApplyRequestDTO.class)))
                .thenThrow(new AccountClosureProcessingException("계좌 해지 신청 저장에 실패했습니다."));

        mockMvc.perform(
                        post("/api/admin/account-closures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 저장에 실패했습니다."));
    }

    @Test
    void concurrentAccountStateChangeReturnsConflict() throws Exception {
        when(accountClosureService.applyClosure(eq(10L), any(AccountClosureApplyRequestDTO.class)))
                .thenThrow(new AccountClosureStateConflictException("계좌 상태가 변경되어 해지를 신청할 수 없습니다."));

        mockMvc.perform(
                        post("/api/admin/account-closures")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("계좌 상태가 변경되어 해지를 신청할 수 없습니다."));

        verify(accountClosureService)
                .applyClosure(eq(10L), any(AccountClosureApplyRequestDTO.class));
    }

    @Test
    void reviewerCanRejectRequestedClosure() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account-closures/30/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"고객 요청 정보가 일치하지 않습니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 반려 완료"));

        verify(accountClosureService).rejectClosure(7L, 30L, "고객 요청 정보가 일치하지 않습니다.");
    }

    @Test
    void blankRejectionReasonReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account-closures/30/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountClosureService);
    }

    @Test
    void nonPositiveClosureRequestIdReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account-closures/0/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"반려 사유\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountClosureService);
    }

    @Test
    void missingClosureRequestReturnsNotFound() throws Exception {
        doThrow(new AccountClosureNotFoundException("계좌 해지 신청을 찾을 수 없습니다."))
                .when(accountClosureService)
                .rejectClosure(7L, 999L, "반려 사유");

        mockMvc.perform(
                        post("/api/admin/account-closures/999/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"반려 사유\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청을 찾을 수 없습니다."));
    }

    @Test
    void reviewerCanApproveRequestedClosure() throws Exception {
        mockMvc.perform(post("/api/admin/account-closures/30/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 승인 완료"));

        verify(accountClosureService).approveClosure(7L, 30L);
    }

    @Test
    void nonPositiveApprovalRequestIdReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/admin/account-closures/0/approve")).andExpect(status().isBadRequest());

        verifyNoInteractions(accountClosureService);
    }

    @Test
    void missingApprovalRequestReturnsNotFound() throws Exception {
        doThrow(new AccountClosureNotFoundException("계좌 해지 신청을 찾을 수 없습니다."))
                .when(accountClosureService)
                .approveClosure(7L, 999L);

        mockMvc.perform(post("/api/admin/account-closures/999/approve"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청을 찾을 수 없습니다."));

        verify(accountClosureService).approveClosure(7L, 999L);
    }

    @Test
    void approvalOfAlreadyProcessedClosureReturnsBadRequest() throws Exception {
        doThrow(new AccountClosureNotAllowedException("이미 처리된 계좌 해지 신청입니다."))
                .when(accountClosureService)
                .approveClosure(7L, 30L);

        mockMvc.perform(post("/api/admin/account-closures/30/approve"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 처리된 계좌 해지 신청입니다."));
    }

    @Test
    void approvalProcessingFailureReturnsInternalServerError() throws Exception {
        doThrow(new AccountClosureProcessingException("계좌 해지 신청 완료 처리에 실패했습니다."))
                .when(accountClosureService)
                .approveClosure(7L, 30L);

        mockMvc.perform(post("/api/admin/account-closures/30/approve"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 완료 처리에 실패했습니다."));
    }

    @Test
    void omittedStatusReturnsRequestedClosures() throws Exception {
        AccountClosureResponseDTO response = response(AccountClosureStatus.REQUESTED);
        when(accountClosureService.getClosures(AccountClosureStatus.REQUESTED))
                .thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/account-closures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 목록 조회 완료"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].closureRequestId").value(30))
                .andExpect(jsonPath("$.data[0].customerName").value("조회 고객"))
                .andExpect(jsonPath("$.data[0].accountNo").value("1234567890"))
                .andExpect(jsonPath("$.data[0].status").value("REQUESTED"));

        verify(accountClosureService).getClosures(AccountClosureStatus.REQUESTED);
    }

    @Test
    void explicitStatusIsPassedToClosureListService() throws Exception {
        when(accountClosureService.getClosures(AccountClosureStatus.REJECTED))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/account-closures").param("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(accountClosureService).getClosures(AccountClosureStatus.REJECTED);
    }

    @Test
    void closureDetailReturnsAllReviewFields() throws Exception {
        when(accountClosureService.getClosure(30L))
                .thenReturn(detailResponse(AccountClosureStatus.REQUESTED));

        mockMvc.perform(get("/api/admin/account-closures/30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청 상세 조회 완료"))
                .andExpect(jsonPath("$.data.closureRequestId").value(30))
                .andExpect(jsonPath("$.data.customerName").value("조회 고객"))
                .andExpect(jsonPath("$.data.accountNo").value("1234567890"))
                .andExpect(jsonPath("$.data.accountAmount").value(1000))
                .andExpect(jsonPath("$.data.destinationGeneralAccountId").value(20))
                .andExpect(jsonPath("$.data.earlyWithdrawalAgreed").value(true))
                .andExpect(jsonPath("$.data.hasImmaturePrincipal").value(true))
                .andExpect(jsonPath("$.data.immaturePrincipalAmount").value(400))
                .andExpect(jsonPath("$.data.taxBenefitCancellationExpected").value(true))
                .andExpect(jsonPath("$.data.taxBenefitCancellationOccurred").value(false))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.requestedAt").value("2026-08-12T09:00:00"));

        verify(accountClosureService).getClosure(30L);
    }

    @Test
    void nonPositiveClosureDetailIdReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/admin/account-closures/0")).andExpect(status().isBadRequest());

        verifyNoInteractions(accountClosureService);
    }

    @Test
    void missingClosureDetailReturnsNotFound() throws Exception {
        when(accountClosureService.getClosure(999L))
                .thenThrow(new AccountClosureNotFoundException("계좌 해지 신청을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/admin/account-closures/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("계좌 해지 신청을 찾을 수 없습니다."));
    }

    private static AccountClosureResponseDTO response(AccountClosureStatus status) {
        return AccountClosureResponseDTO.builder()
                .closureRequestId(30L)
                .customerName("조회 고객")
                .accountNo("1234567890")
                .status(status)
                .requestedAt(LocalDateTime.of(2026, 8, 12, 9, 0))
                .build();
    }

    private static AccountClosureDetailResponseDTO detailResponse(AccountClosureStatus status) {
        return AccountClosureDetailResponseDTO.builder()
                .closureRequestId(30L)
                .customerName("조회 고객")
                .accountNo("1234567890")
                .accountAmount(new BigDecimal("1000"))
                .destinationGeneralAccountId(20L)
                .earlyWithdrawalAgreed(true)
                .hasImmaturePrincipal(true)
                .immaturePrincipalAmount(new BigDecimal("400"))
                .taxBenefitCancellationExpected(true)
                .taxBenefitCancellationOccurred(false)
                .status(status)
                .requestedAt(LocalDateTime.of(2026, 8, 12, 9, 0))
                .build();
    }

    private static String validRequest() {
        return """
                {
                  "customerId": 10,
                  "destinationGeneralAccountId": 20,
                  "earlyWithdrawalAgreed": true
                }
                """;
    }
}
