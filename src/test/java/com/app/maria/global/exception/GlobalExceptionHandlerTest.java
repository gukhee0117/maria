package com.app.maria.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
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
                .andExpect(
                        jsonPath("$.code")
                                .value(ErrorType.EARLY_WITHDRAWAL_CONSENT_REQUIRED.name()))
                .andExpect(
                        jsonPath("$.message")
                                .value(ErrorType.EARLY_WITHDRAWAL_CONSENT_REQUIRED.getMessage()));
    }

    @Test
    void accountClosureNotAllowedReturnsDistinctErrorCode() throws Exception {
        mockMvc.perform(get("/test/account-closure-not-allowed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorType.ACCOUNT_CLOSURE_NOT_ALLOWED.name()))
                .andExpect(
                        jsonPath("$.message")
                                .value(ErrorType.ACCOUNT_CLOSURE_NOT_ALLOWED.getMessage()));
    }

    @RestController
    static class ExceptionThrowingController {

        @GetMapping("/test/early-withdrawal-consent")
        void requireEarlyWithdrawalConsent() {
            throw new AppException(ErrorType.EARLY_WITHDRAWAL_CONSENT_REQUIRED);
        }

        @GetMapping("/test/account-closure-not-allowed")
        void rejectAccountClosure() {
            throw new AppException(ErrorType.ACCOUNT_CLOSURE_NOT_ALLOWED);
        }
    }
}
