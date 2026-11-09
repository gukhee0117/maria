package com.app.maria.domain.account.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.account.dto.response.AccountResponseDTO;
import com.app.maria.domain.account.service.AccountService;
import com.app.maria.domain.account.service.MydataConsentService;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerAccountApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerAccountApiTest {
    private static final String URL = "/api/customer/account/applications";
    @Autowired private MockMvc mvc;
    @MockitoBean private AccountService accountService;
    @MockitoBean private MydataConsentService mydataConsentService;

    private RequestPostProcessor customer() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(1L, "customer@test.com", "고객"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    void appliesForSessionCustomerEvenIfBodyContainsAnotherCustomerId() throws Exception {
        when(accountService.applyCustomerAccount(1L, new BigDecimal("30000000"), true))
                .thenReturn(
                        AccountResponseDTO.builder().customerId(1L).status(Status.OPENED).build());
        mvc.perform(
                        post(URL)
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerId\":999,\"limitAmount\":30000000,\"mydataConsent\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customerId").value(1))
                .andExpect(jsonPath("$.data.status").value("OPENED"));
        verify(accountService).applyCustomerAccount(1L, new BigDecimal("30000000"), true);
        verifyNoMoreInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "50000000"})
    void acceptsLimitBoundaries(String amount) throws Exception {
        mvc.perform(
                        post(URL)
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"limitAmount\":" + amount + ",\"mydataConsent\":true}"))
                .andExpect(status().isCreated());
        verify(accountService).applyCustomerAccount(1L, new BigDecimal(amount), true);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"limitAmount\":null}",
                "{\"limitAmount\":0}",
                "{\"limitAmount\":-1}",
                "{\"limitAmount\":50000001}",
                "{\"limitAmount\":1.5}"
            })
    void rejectsInvalidLimitsBeforeCallingService(String body) throws Exception {
        mvc.perform(
                        post(URL)
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "admin", "missing-csrf"})
    void rejectsRequestsWithoutCustomerAuthorizationOrCsrf(String mode) throws Exception {
        var request =
                post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"limitAmount\":1}");
        if (!mode.equals("missing-csrf")) request.with(csrf());
        if (mode.equals("missing-csrf")) request.with(customer());
        if (mode.equals("admin"))
            request.with(
                    authentication(
                            new UsernamePasswordAuthenticationToken(
                                    99L,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")))));
        mvc.perform(request).andExpect(status().is(mode.equals("anonymous") ? 401 : 403));
        verifyNoInteractions(accountService);
    }

    @Test
    void availableLimitUsesSessionCustomer() throws Exception {
        when(accountService.getCustomerAvailableLimit(1L)).thenReturn(new BigDecimal("40000000"));
        mvc.perform(get("/api/customer/account/available-limit?customerId=999").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(40000000));
        verify(accountService).getCustomerAvailableLimit(1L);
    }

    @Test
    void applicationWithoutConsentFieldPassesFalseToService() throws Exception {
        when(accountService.applyCustomerAccount(1L, BigDecimal.ONE, false))
                .thenThrow(new AppException(ErrorType.ACCOUNT_MYDATA_CONSENT_REQUIRED, 1L));
        mvc.perform(
                        post(URL)
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"limitAmount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_MYDATA_CONSENT_REQUIRED"));
    }

    @Test
    void mydataConsentIsReadAndSavedForSessionCustomer() throws Exception {
        when(mydataConsentService.isAgreed(1L)).thenReturn(true);
        mvc.perform(get("/api/customer/account/mydata-consent?customerId=999").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mvc.perform(
                        put("/api/customer/account/mydata-consent")
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agreed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
        verify(mydataConsentService).record(1L, false);
    }

    @Test
    void mydataConsentRequiresAgreedFieldAndCsrf() throws Exception {
        mvc.perform(
                        put("/api/customer/account/mydata-consent")
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/customer/account/mydata-consent")
                                .with(customer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agreed\":true}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mydataConsentService);
    }

    @Test
    void duplicateApplicationUsesExistingErrorResponse() throws Exception {
        when(accountService.applyCustomerAccount(1L, BigDecimal.ONE, true))
                .thenThrow(new AppException(ErrorType.ACCOUNT_ALREADY_EXISTS, 1L));
        mvc.perform(
                        post(URL)
                                .with(customer())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"limitAmount\":1,\"mydataConsent\":true}"))
                .andExpect(status().isConflict());
    }
}
