package com.app.maria.domain.withdrawal.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import com.app.maria.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WithdrawalApiTest {
    @Mock private WithdrawalQueryService withdrawalQueryService;
    @Mock private WithdrawalService withdrawalService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new WithdrawalApi(withdrawalQueryService, withdrawalService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(
                                        Jackson2ObjectMapperBuilder.json()
                                                .featuresToDisable(
                                                        SerializationFeature
                                                                .WRITE_DATES_AS_TIMESTAMPS)
                                                .build()))
                        .build();
    }

    @Test
    void postWithdrawalReturnsCreatedResult() throws Exception {
        when(withdrawalService.withdraw(any()))
                .thenReturn(
                        WithdrawalResultDTO.builder()
                                .withdrawalId(10L)
                                .allocations(List.of())
                                .build());

        mockMvc.perform(
                        post("/api/withdrawals")
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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.withdrawalId").value(10));

        verify(withdrawalService).withdraw(any());
    }

    @Test
    void postWithdrawalRejectsNonPositiveAmount() throws Exception {
        mockMvc.perform(
                        post("/api/withdrawals")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "accountId": 1,
                                          "requestedAmount": 0,
                                          "earlyWithdrawalAgreed": false,
                                          "destinationGeneralAccountId": 20
                                        }
                                        """))
                .andExpect(status().isBadRequest());

        verify(withdrawalService, org.mockito.Mockito.never()).withdraw(any());
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

        mockMvc.perform(get("/api/withdrawals").param("status", "COMPLETED"))
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

        mockMvc.perform(get("/api/withdrawals/10"))
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

        mockMvc.perform(get("/api/withdrawals/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"));

        verify(withdrawalQueryService).getWithdrawalsByAccountId(1L);
    }

    @Test
    void nonPositiveAccountIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/withdrawals/accounts/0")).andExpect(status().isBadRequest());
    }

    @Test
    void missingWithdrawalReturnsNotFound() throws Exception {
        when(withdrawalQueryService.getWithdrawal(99L))
                .thenThrow(new WithdrawalNotFoundException("인출 내역을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/withdrawals/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("인출 내역을 찾을 수 없습니다."));
    }

    @Test
    void nonPositiveWithdrawalIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/withdrawals/0")).andExpect(status().isBadRequest());
    }
}
