package com.app.maria.domain.tax.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@WebMvcTest(
        value = CustomerTaxBriefingApi.class,
        properties = "spring.config.location=optional:classpath:/application-webmvctest.yml")
@Import(CustomerSecurityConfig.class)
class CustomerTaxBriefingApiTest {

    private static final Long CUSTOMER_ID = 7L;
    private static final CustomerPrincipal PRINCIPAL =
            new CustomerPrincipal(CUSTOMER_ID, "customer@test.com", "테스트고객");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TaxCalculationService taxCalculationService;

    private static RequestPostProcessor customerAuth() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return authentication(authentication);
    }

    @Test
    void 미인증_고객은_조회할수없다() throws Exception {
        mockMvc.perform(get("/api/customer/tax/briefing")).andExpect(status().isUnauthorized());

        verify(taxCalculationService, never()).getCustomerTaxBriefing(CUSTOMER_ID);
    }

    @Test
    void 로그인한_고객의_절세브리핑을_조회한다() throws Exception {
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 10, 28, 10, 30);
        CustomerTaxBriefingResponseDTO response = briefingResponse(calculatedAt);
        when(taxCalculationService.getCustomerTaxBriefing(CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/customer/tax/briefing").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("절세 브리핑 조회 성공"))
                .andExpect(jsonPath("$.data.accountId").value(11L))
                .andExpect(jsonPath("$.data.current.generalTax").value(3850000.00))
                .andExpect(jsonPath("$.data.current.riaTax").value(0))
                .andExpect(jsonPath("$.data.current.reliefAmount").value(3850000.00))
                .andExpect(jsonPath("$.data.expected.generalTax").value(5445000.00))
                .andExpect(jsonPath("$.data.expected.riaTax").value(247500.00))
                .andExpect(jsonPath("$.data.expected.reliefAmount").value(5197500.00))
                .andExpect(jsonPath("$.data.expected.additionalReliefAmount").value(1347500.00))
                .andExpect(jsonPath("$.data.expected.expectedFinalAt").value("2026-10-30"))
                .andExpect(jsonPath("$.data.calculatedAt").value("2026-10-28T10:30:00"));

        verify(taxCalculationService).getCustomerTaxBriefing(CUSTOMER_ID);
    }

    @Test
    void 예상_세액을_계산하지_못해도_현재_세액을_응답한다() throws Exception {
        CustomerTaxBriefingResponseDTO response =
                CustomerTaxBriefingResponseDTO.of(
                        11L,
                        CustomerTaxBriefingResponseDTO.TaxScenario.of(
                                new BigDecimal("3850000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("3850000.00")),
                        null,
                        LocalDateTime.of(2026, 10, 28, 10, 30));
        when(taxCalculationService.getCustomerTaxBriefing(CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/customer/tax/briefing").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.reliefAmount").value(3850000.00))
                .andExpect(jsonPath("$.data.expected").value(nullValue()));

        verify(taxCalculationService).getCustomerTaxBriefing(CUSTOMER_ID);
    }

    private CustomerTaxBriefingResponseDTO briefingResponse(LocalDateTime calculatedAt) {
        return CustomerTaxBriefingResponseDTO.of(
                11L,
                CustomerTaxBriefingResponseDTO.TaxScenario.of(
                        new BigDecimal("3850000.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("3850000.00")),
                CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.of(
                        new BigDecimal("5445000.00"),
                        new BigDecimal("247500.00"),
                        new BigDecimal("5197500.00"),
                        new BigDecimal("1347500.00"),
                        LocalDate.of(2026, 10, 30)),
                calculatedAt);
    }
}
