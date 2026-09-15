package com.app.maria.domain.withdrawal.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalAllocationResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.exception.WithdrawalNotFoundException;
import com.app.maria.domain.withdrawal.service.WithdrawalQueryService;
import com.app.maria.domain.withdrawal.service.WithdrawalService;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.domain.withdrawal.type.WithdrawalType;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WithdrawalApi.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "VIEWER")
class WithdrawalApiTest {
    @Autowired private MockMvc mockMvc;

    @MockitoBean private WithdrawalQueryService withdrawalQueryService;
    @MockitoBean private WithdrawalService withdrawalService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "REVIEWER", "SETTLEMENT", "VIEWER"})
    void adminRolesCannotExecuteWithdrawal(String role) throws Exception {
        mockMvc.perform(
                        post("/api/admin/withdrawals")
                                .with(user("admin").roles(role))
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "accountId": 1,
                                          "requestedAmount": 800,
                                          "earlyWithdrawalAgreed": false,
                                          "destinationGeneralAccountId": 20
                                        }
                                        """))
                .andExpect(status().isForbidden());

        verify(withdrawalService, never()).withdraw(any());
    }

    @Test
    void statusFilterReturnsWithdrawalSummary() throws Exception {
        WithdrawalListResponseDTO response =
                WithdrawalListResponseDTO.builder()
                        .withdrawalId(10L)
                        .customerName("인출 고객")
                        .riaAccountNo("1234567890")
                        .requestedAmount(new BigDecimal("800"))
                        .destinationAccountNo("111122223333")
                        .status(WithdrawalStatus.COMPLETED)
                        .processedAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                        .earningsAmount(new BigDecimal("100"))
                        .maturedPrincipalAmount(new BigDecimal("300"))
                        .immaturePrincipalAmount(new BigDecimal("400"))
                        .build();
        when(withdrawalQueryService.getWithdrawals(WithdrawalStatus.COMPLETED))
                .thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/withdrawals").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].withdrawalId").value(10))
                .andExpect(jsonPath("$.data[0].customerName").value("인출 고객"))
                .andExpect(jsonPath("$.data[0].requestedAmount").value(800))
                .andExpect(jsonPath("$.data[0].immaturePrincipalAmount").value(400));

        verify(withdrawalQueryService).getWithdrawals(WithdrawalStatus.COMPLETED);
    }

    @Test
    void detailReturnsEarlyWithdrawalResult() throws Exception {
        WithdrawalAllocationResponseDTO allocation =
                WithdrawalAllocationResponseDTO.builder()
                        .allocationId(20L)
                        .leftAmountId(30L)
                        .exchangeId(40L)
                        .allocatedAmount(new BigDecimal("400"))
                        .withdrawalAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                        .type(WithdrawalType.IMMATURE_PRINCIPAL_INCLUDED)
                        .finalAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .maturityAt(LocalDateTime.of(2027, 1, 1, 9, 0))
                        .productName("Apple")
                        .ticker("AAPL")
                        .build();
        WithdrawalDetailResponseDTO response =
                WithdrawalDetailResponseDTO.builder()
                        .withdrawalId(10L)
                        .earlyWithdrawal(true)
                        .allocations(List.of(allocation))
                        .build();
        when(withdrawalQueryService.getWithdrawal(10L)).thenReturn(response);

        mockMvc.perform(get("/api/admin/withdrawals/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawalId").value(10))
                .andExpect(jsonPath("$.data.earlyWithdrawal").value(true))
                .andExpect(jsonPath("$.data.allocations[0].exchangeId").value(40))
                .andExpect(jsonPath("$.data.allocations[0].allocatedAmount").value(400))
                .andExpect(
                        jsonPath("$.data.allocations[0].type").value("IMMATURE_PRINCIPAL_INCLUDED"))
                .andExpect(jsonPath("$.data.allocations[0].finalAt").value("2026-01-01T09:00:00"))
                .andExpect(jsonPath("$.data.allocations[0].productName").value("Apple"))
                .andExpect(jsonPath("$.data.allocations[0].ticker").value("AAPL"))
                .andExpect(
                        jsonPath("$.data.allocations[0].maturityAt").value("2027-01-01T09:00:00"));
    }

    @Test
    void accountHistoryReturnsCompletedAndFailedWithdrawals() throws Exception {
        WithdrawalListResponseDTO completed =
                WithdrawalListResponseDTO.builder()
                        .withdrawalId(10L)
                        .status(WithdrawalStatus.COMPLETED)
                        .requestedAmount(new BigDecimal("800"))
                        .build();
        WithdrawalListResponseDTO failed =
                WithdrawalListResponseDTO.builder()
                        .withdrawalId(11L)
                        .status(WithdrawalStatus.FAILED)
                        .requestedAmount(new BigDecimal("900"))
                        .build();
        when(withdrawalQueryService.getWithdrawalsByAccountId(1L))
                .thenReturn(List.of(completed, failed));

        mockMvc.perform(get("/api/admin/withdrawals/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"));

        verify(withdrawalQueryService).getWithdrawalsByAccountId(1L);
    }

    @Test
    void nonPositiveAccountIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/withdrawals/accounts/0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingWithdrawalReturnsNotFound() throws Exception {
        when(withdrawalQueryService.getWithdrawal(99L))
                .thenThrow(new WithdrawalNotFoundException("인출 내역을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/admin/withdrawals/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("인출 내역을 찾을 수 없습니다."));
    }

    @Test
    void nonPositiveWithdrawalIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/withdrawals/0")).andExpect(status().isBadRequest());
    }
}
