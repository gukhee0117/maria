package com.app.maria.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @ParameterizedTest
    @EnumSource(
            value = ErrorType.class,
            names = {
                "ACCOUNT_NOT_FOUND",
                "ACCOUNT_CUSTOMER_NOT_FOUND",
                "ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND",
                "ACCOUNT_ALREADY_EXISTS",
                "ACCOUNT_APPLICATION_PERIOD_CLOSED",
                "ACCOUNT_LIMIT_REQUIRED",
                "ACCOUNT_LIMIT_OUT_OF_RANGE",
                "ACCOUNT_LIMIT_NOT_WHOLE_WON",
                "ACCOUNT_LIMIT_EXCEEDS_AVAILABLE",
                "ACCOUNT_LIMIT_BELOW_USED_AMOUNT",
                "ACCOUNT_LIMIT_UNCHANGED",
                "ACCOUNT_STATE_NOT_ALLOWED",
                "ACCOUNT_CONCURRENT_MODIFICATION",
                "ACCOUNT_APPLICATION_SAVE_FAILED",
                "ACCOUNT_AMOUNT_UPDATE_FAILED",
                "ACCOUNT_NUMBER_GENERATION_FAILED",
                "ACCOUNT_STATUS_LOG_SAVE_FAILED",
                "ACCOUNT_BENEFIT_LOG_SAVE_FAILED",
                "ACCOUNT_STATE_UPDATE_FAILED",
                "ACCOUNT_BENEFIT_UPDATE_FAILED"
            })
    void accountErrorsReturnConfiguredStatusCodeAndMessage(ErrorType errorType) throws Exception {
        mockMvc.perform(get("/test/account-error/{errorType}", errorType.name()))
                .andExpect(status().is(errorType.getStatus().value()))
                .andExpect(jsonPath("$.code").value(errorType.name()))
                .andExpect(jsonPath("$.message").value(errorType.getMessage()));
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

        @GetMapping("/test/account-error/{errorType}")
        void throwAccountError(@PathVariable ErrorType errorType) {
            throw new AppException(errorType, 1L);
        }
    }
}
