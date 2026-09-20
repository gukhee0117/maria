package com.app.maria.domain.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.dto.request.AccountRequestDTO;
import com.app.maria.domain.account.dto.request.ReasonRequestDTO;
import com.app.maria.domain.account.dto.response.AccountLimitUsageResponseDTO;
import com.app.maria.domain.account.dto.response.AccountResponseDTO;
import com.app.maria.domain.account.service.AccountService;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountApiTest {

    private AccountService accountService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new AccountApi(accountService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void accountApiUsesAdminRolePolicy() throws Exception {
        assertThat(AccountApi.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')");

        assertThat(
                        AccountApi.class
                                .getDeclaredMethod("approve", Long.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("hasRole('REVIEWER')");
        assertThat(
                        AccountApi.class
                                .getDeclaredMethod("reject", Long.class, ReasonRequestDTO.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("hasRole('REVIEWER')");
        assertThat(
                        AccountApi.class
                                .getDeclaredMethod("override", Long.class, ReasonRequestDTO.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("hasRole('REVIEWER')");

        assertThat(
                        AccountApi.class
                                .getDeclaredMethod("apply", AccountRequestDTO.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("hasRole('REVIEWER')");
        assertThat(
                        AccountApi.class
                                .getDeclaredMethod(
                                        "reapply", Long.class, AccountReapplyRequestDTO.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("hasRole('REVIEWER')");
        assertThat(
                        AccountApi.class
                                .getDeclaredMethod("getAccount", Long.class)
                                .getAnnotation(PreAuthorize.class))
                .isNull();
    }

    @Test
    void applyAcceptsValidRequest() throws Exception {
        AccountResponseDTO response = mock(AccountResponseDTO.class);
        when(accountService.applyAccount(any(AccountRequestDTO.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/admin/account/applications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "customerId": 1,
                  "limitAmount": 30000000
                }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("계좌 개설 신청 처리 완료"));

        verify(accountService).applyAccount(any(AccountRequestDTO.class));
    }

    @Test
    void getAccountsRequiringActionCountReturnsCount() throws Exception {
        when(accountService.getAccountsRequiringActionCount()).thenReturn(3);

        mockMvc.perform(get("/api/admin/account/requiring-action-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("처리 필요 계좌 건수 조회"))
                .andExpect(jsonPath("$.data").value(3));

        verify(accountService).getAccountsRequiringActionCount();
    }

    @Test
    void getAccountReturnsLatestAccount() throws Exception {
        AccountResponseDTO response =
                AccountResponseDTO.builder()
                        .accountId(1L)
                        .customerId(10L)
                        .status(Status.OPENED)
                        .accountNo("1234567890")
                        .amount(BigDecimal.valueOf(1_000_000L))
                        .build();
        when(accountService.getAccountByAccountId(1L)).thenReturn(response);

        mockMvc.perform(get("/api/admin/account/{accountId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 조회"))
                .andExpect(jsonPath("$.data.accountId").value(1L))
                .andExpect(jsonPath("$.data.amount").value(1_000_000L));

        verify(accountService).getAccountByAccountId(1L);
    }

    @Test
    void applyRejectsMissingCustomerId() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/applications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "limitAmount": 30000000
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("개설할 계좌의 사용자 정보는 필수입니다."));

        verify(accountService, never()).applyAccount(any());
    }

    @Test
    void applyRejectsNonPositiveCustomerId() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/applications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "customerId": 0,
                  "limitAmount": 30000000
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사용자 ID는 0보다 커야 합니다."));

        verify(accountService, never()).applyAccount(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "50000001", "1.5"})
    void applyRejectsInvalidLimit(String limitAmount) throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/applications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "customerId": 1,
                  "limitAmount": %s
                }
                """
                                                .formatted(limitAmount)))
                .andExpect(status().isBadRequest());

        verify(accountService, never()).applyAccount(any());
    }

    @Test
    void applyRejectsMissingLimit() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/applications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "customerId": 1
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("계좌 한도는 필수입니다."));

        verify(accountService, never()).applyAccount(any());
    }

    @Test
    void updateLimitRequiresExpectedCurrentLimit() throws Exception {
        mockMvc.perform(
                        put("/api/admin/account/update/limit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "customerId": 1,
                  "limitAmount": 40000000
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("현재 계좌 한도 입력이 필요합니다."));

        verify(accountService, never()).updateAccountLimit(any());
    }

    @Test
    void getAvailableLimitRejectsNonPositiveCustomerId() throws Exception {
        mockMvc.perform(get("/api/admin/account/available-limit").param("customerId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사용자 ID는 0보다 커야 합니다."));

        verify(accountService, never()).getAvailableLimit(any());
    }

    @Test
    void approveRejectsNonPositiveAccountId() throws Exception {
        mockMvc.perform(post("/api/admin/account/0/approve"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("계좌 ID는 0보다 커야 합니다."));

        verify(accountService, never()).approveAccount(anyLong());
    }

    @Test
    void rejectRejectsBlankReason() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/1/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "reason": "   "
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사유를 입력해야 합니다."));

        verify(accountService, never()).rejectAccount(anyLong(), anyString());
    }

    @Test
    void rejectRejectsReasonLongerThanTwoHundredCharacters() throws Exception {
        String reason = "a".repeat(201);

        mockMvc.perform(
                        post("/api/admin/account/1/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "reason": "%s"
                }
                """
                                                .formatted(reason)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사유는 200자 이하로 입력해야 합니다."));

        verify(accountService, never()).rejectAccount(anyLong(), anyString());
    }

    @Test
    void reapplyAcceptsValidRequest() throws Exception {
        AccountResponseDTO response = mock(AccountResponseDTO.class);
        when(accountService.reapplyAccountByAccountId(
                        anyLong(), any(AccountReapplyRequestDTO.class)))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/admin/account/1/reapply")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "limitAmount": 20000000
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 재신청"));

        verify(accountService)
                .reapplyAccountByAccountId(anyLong(), any(AccountReapplyRequestDTO.class));
    }

    @Test
    void reapplyAcceptsMissingLimitForCurrentLimitReuse() throws Exception {
        AccountResponseDTO response = mock(AccountResponseDTO.class);
        when(accountService.reapplyAccountByAccountId(
                        anyLong(), any(AccountReapplyRequestDTO.class)))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/admin/account/1/reapply")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 재신청"));

        verify(accountService)
                .reapplyAccountByAccountId(anyLong(), any(AccountReapplyRequestDTO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "50000001", "1.5"})
    void reapplyRejectsInvalidLimit(String limitAmount) throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/1/reapply")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "limitAmount": %s
                }
                """
                                                .formatted(limitAmount)))
                .andExpect(status().isBadRequest());

        verify(accountService, never())
                .reapplyAccountByAccountId(anyLong(), any(AccountReapplyRequestDTO.class));
    }

    @Test
    void overrideRejectsBlankReason() throws Exception {
        mockMvc.perform(
                        post("/api/admin/account/1/override")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "reason": ""
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사유를 입력해야 합니다."));

        verify(accountService, never()).overrideAccount(anyLong(), anyString());
    }

    @Test
    void searchAcceptsAccountNoOnlyAndReturnsMatchedAccounts() throws Exception {
        AccountLimitUsageResponseDTO response =
                AccountLimitUsageResponseDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .status(Status.OPENED)
                        .limitAmount(BigDecimal.valueOf(30_000_000L))
                        .usedAmount(BigDecimal.ZERO)
                        .build();
        when(accountService.searchAccounts(any())).thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/account/search").param("accountNo", "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 검색"))
                .andExpect(jsonPath("$.data[0].accountNo").value("1234567890"))
                .andExpect(jsonPath("$.data[0].customerName").value("홍길동"));

        verify(accountService).searchAccounts(any());
    }

    @Test
    void searchRejectsRequestWithoutAccountNoOrCustomerName() throws Exception {
        mockMvc.perform(get("/api/admin/account/search")).andExpect(status().isBadRequest());

        verify(accountService, never()).searchAccounts(any());
    }
}
