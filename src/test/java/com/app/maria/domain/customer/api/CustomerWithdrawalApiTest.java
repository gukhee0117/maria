package com.app.maria.domain.customer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.service.CustomerWithdrawalService;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerWithdrawalApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerWithdrawalApiTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerWithdrawalService customerWithdrawalService;

    private static final CustomerPrincipal PRINCIPAL =
            new CustomerPrincipal(1L, "customer@test.com", "테스트고객");

    private static RequestPostProcessor customerAuth() {
        Authentication auth =
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return authentication(auth);
    }

    // ── 보안 ────────────────────────────────────────────────────────────────

    @Test
    void unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/customer/withdrawals")).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutCsrf_returnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/api/customer/withdrawals")
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "requestedAmount": 100000,
                                          "destinationGeneralAccountId": 10
                                        }
                                        """))
                .andExpect(status().isForbidden());

        verify(customerWithdrawalService, never()).withdraw(any(), any());
    }

    // ── 목적지 계좌 목록 ──────────────────────────────────────────────────

    @Test
    void getDestinationAccounts_returnsAccountList() throws Exception {
        GeneralAccountResponseDTO account =
                GeneralAccountResponseDTO.builder()
                        .generalAccountId(10L)
                        .accountNo("1234567890")
                        .status(GeneralAccountStatus.ACTIVE)
                        .build();
        when(customerWithdrawalService.getDestinationAccounts(1L)).thenReturn(List.of(account));

        mockMvc.perform(get("/api/customer/general-accounts").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].generalAccountId").value(10))
                .andExpect(jsonPath("$.data[0].accountNo").value("1234567890"));

        verify(customerWithdrawalService).getDestinationAccounts(1L);
    }

    // ── 인출 신청 ─────────────────────────────────────────────────────────

    @Test
    void withdraw_returnsWithdrawalResult() throws Exception {
        WithdrawalResultDTO result =
                WithdrawalResultDTO.builder()
                        .withdrawalId(5L)
                        .allocations(
                                List.of(
                                        WithdrawalAllocationDTO.builder()
                                                .allocatedAmount(new BigDecimal("100000"))
                                                .build()))
                        .build();
        when(customerWithdrawalService.withdraw(eq(1L), any())).thenReturn(result);

        mockMvc.perform(
                        post("/api/customer/withdrawals")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "requestedAmount": 100000,
                                          "destinationGeneralAccountId": 10,
                                          "earlyWithdrawalAgreed": false
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawalId").value(5));

        verify(customerWithdrawalService).withdraw(eq(1L), any());
    }

    @Test
    void withdraw_missingAmount_returnsBadRequest() throws Exception {
        mockMvc.perform(
                        post("/api/customer/withdrawals")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "destinationGeneralAccountId": 10
                                        }
                                        """))
                .andExpect(status().isBadRequest());

        verify(customerWithdrawalService, never()).withdraw(any(), any());
    }

    // ── 인출 이력 ─────────────────────────────────────────────────────────

    @Test
    void getWithdrawals_returnsList() throws Exception {
        WithdrawalListResponseDTO item =
                WithdrawalListResponseDTO.builder()
                        .withdrawalId(7L)
                        .requestedAmount(new BigDecimal("200000"))
                        .status(WithdrawalStatus.COMPLETED)
                        .processedAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                        .build();
        when(customerWithdrawalService.getWithdrawals(1L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/customer/withdrawals").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].withdrawalId").value(7))
                .andExpect(jsonPath("$.data[0].requestedAmount").value(200000))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"));

        verify(customerWithdrawalService).getWithdrawals(1L);
    }

    // ── 인출 상세 ─────────────────────────────────────────────────────────

    @Test
    void getWithdrawal_returnsDetail() throws Exception {
        WithdrawalDetailResponseDTO detail =
                WithdrawalDetailResponseDTO.builder()
                        .withdrawalId(7L)
                        .requestedAmount(new BigDecimal("200000"))
                        .status(WithdrawalStatus.COMPLETED)
                        .earlyWithdrawal(false)
                        .allocations(List.of())
                        .build();
        when(customerWithdrawalService.getWithdrawal(1L, 7L)).thenReturn(detail);

        mockMvc.perform(get("/api/customer/withdrawals/7").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawalId").value(7))
                .andExpect(jsonPath("$.data.earlyWithdrawal").value(false));

        verify(customerWithdrawalService).getWithdrawal(1L, 7L);
    }

    @Test
    void getWithdrawal_otherCustomersOrMissing_returnsNotFound() throws Exception {
        when(customerWithdrawalService.getWithdrawal(1L, 99L))
                .thenThrow(new AppException(ErrorType.WITHDRAWAL_NOT_FOUND));

        mockMvc.perform(get("/api/customer/withdrawals/99").with(customerAuth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getWithdrawal_nonPositiveId_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/customer/withdrawals/0").with(customerAuth()))
                .andExpect(status().isBadRequest());
    }
}
