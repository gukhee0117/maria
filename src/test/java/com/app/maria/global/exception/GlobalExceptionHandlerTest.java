package com.app.maria.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.accountclosure.exception.AccountClosureNotAllowedException;
import com.app.maria.domain.withdrawal.exception.EarlyWithdrawalConsentRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new ExceptionThrowingController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void earlyWithdrawalConsentRequiredReturnsDistinctErrorCode() throws Exception {
        mockMvc.perform(get("/test/early-withdrawal-consent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EARLY_WITHDRAWAL_CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.message").value("조기인출 동의가 필요합니다."));
    }

    @Test
    void accountClosureNotAllowedReturnsDistinctErrorCode() throws Exception {
        mockMvc.perform(get("/test/account-closure-not-allowed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSURE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("계좌를 해지할 수 없습니다."));
    }

    @RestController
    static class ExceptionThrowingController {

        @GetMapping("/test/early-withdrawal-consent")
        void requireEarlyWithdrawalConsent() {
            throw new EarlyWithdrawalConsentRequiredException("조기인출 동의가 필요합니다.");
        }

        @GetMapping("/test/account-closure-not-allowed")
        void rejectAccountClosure() {
            throw new AccountClosureNotAllowedException("계좌를 해지할 수 없습니다.");
        }
    }
}
