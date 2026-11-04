package com.app.maria.domain.tax.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.response.CustomerTaxBasisResponseDTO;
import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void expectedReliefRequiresCustomerAuthentication() throws Exception {
        mockMvc.perform(get("/api/customer/tax/briefing/expected-relief"))
                .andExpect(status().isUnauthorized());
        verify(taxCalculationService, never()).getCustomerExpectedTaxBriefing(CUSTOMER_ID);
    }

    @Test
    void expectedReliefUsesAuthenticatedCustomerInsteadOfSuppliedAccount() throws Exception {
        var expected = briefingResponse(LocalDateTime.of(2026, 10, 28, 10, 30)).getExpected();
        when(taxCalculationService.getCustomerExpectedTaxBriefing(CUSTOMER_ID))
                .thenReturn(expected);
        mockMvc.perform(
                        get("/api/customer/tax/briefing/expected-relief")
                                .param("accountId", "999")
                                .with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reliefAmount").value(5197500.00))
                .andExpect(jsonPath("$.data.holdingCount").value(2));
        verify(taxCalculationService).getCustomerExpectedTaxBriefing(CUSTOMER_ID);
    }

    private static RequestPostProcessor customerAuth() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return authentication(authentication);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void zeroTaxStillReportsWhetherHoldingsExist(int holdingCount) throws Exception {
        var expected =
                CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.of(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        LocalDate.of(2026, 11, 20),
                        holdingCount);
        when(taxCalculationService.getCustomerExpectedTaxBriefing(CUSTOMER_ID))
                .thenReturn(expected);

        mockMvc.perform(get("/api/customer/tax/briefing/expected-relief").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reliefAmount").value(0))
                .andExpect(jsonPath("$.data.holdingCount").value(holdingCount));
    }

    @Test
    void cumulativeProjectionExposesFinalizedReliefAndSignedChange() throws Exception {
        var expected =
                CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.builder()
                        .generalTax(new BigDecimal("110000"))
                        .riaTax(BigDecimal.ZERO)
                        .reliefAmount(new BigDecimal("110000"))
                        .finalizedReliefAmount(new BigDecimal("330000"))
                        .additionalReliefAmount(new BigDecimal("-220000"))
                        .expectedFinalAt(LocalDate.of(2026, 11, 20))
                        .holdingCount(1)
                        .build();
        when(taxCalculationService.getCustomerExpectedTaxBriefing(CUSTOMER_ID))
                .thenReturn(expected);

        mockMvc.perform(get("/api/customer/tax/briefing/expected-relief").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generalTax").value(110000))
                .andExpect(jsonPath("$.data.finalizedReliefAmount").value(330000))
                .andExpect(jsonPath("$.data.additionalReliefAmount").value(-220000));
    }

    @Test
    void 미인증_고객은_조회할수없다() throws Exception {
        mockMvc.perform(get("/api/customer/tax/briefing")).andExpect(status().isUnauthorized());

        verify(taxCalculationService, never()).getCustomerTaxBriefing(CUSTOMER_ID);
    }

    @Test
    void 로그인한_고객의_절세브리핑을_조회한다() throws Exception {
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 10, 28, 10, 30);
        CustomerTaxBriefingResponseDTO response =
                CustomerTaxBriefingResponseDTO.of(
                        11L, briefingResponse(calculatedAt).getCurrent(), null, calculatedAt);
        when(taxCalculationService.getCustomerTaxBriefing(CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/customer/tax/briefing").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("절세 브리핑 조회 성공"))
                .andExpect(jsonPath("$.data.accountId").value(11L))
                .andExpect(jsonPath("$.data.current.generalTax").value(3850000.00))
                .andExpect(jsonPath("$.data.current.riaTax").value(0))
                .andExpect(jsonPath("$.data.current.reliefAmount").value(3850000.00))
                .andExpect(jsonPath("$.data.expected").value(nullValue()))
                .andExpect(jsonPath("$.data.calculatedAt").value("2026-10-28T10:30:00"));

        verify(taxCalculationService).getCustomerTaxBriefing(CUSTOMER_ID);
    }

    @Test
    void 예상_API가_실패해도_현재_브리핑을_조회할수있다() throws Exception {
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
        when(taxCalculationService.getCustomerExpectedTaxBriefing(CUSTOMER_ID))
                .thenThrow(new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND));

        mockMvc.perform(get("/api/customer/tax/briefing/expected-relief").with(customerAuth()))
                .andExpect(status().isBadGateway());

        mockMvc.perform(get("/api/customer/tax/briefing").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.reliefAmount").value(3850000.00))
                .andExpect(jsonPath("$.data.expected").value(nullValue()));

        verify(taxCalculationService).getCustomerTaxBriefing(CUSTOMER_ID);
        verify(taxCalculationService).getCustomerExpectedTaxBriefing(CUSTOMER_ID);
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
                        null,
                        LocalDate.of(2026, 10, 30),
                        2),
                calculatedAt);
    }

    @Test
    void basisRequiresCustomerAuthentication() throws Exception {
        mockMvc.perform(get("/api/customer/tax/basis")).andExpect(status().isUnauthorized());
        verify(taxCalculationService, never()).getCustomerTaxBasis(CUSTOMER_ID);
    }

    @Test
    void basisReturnsFiveStepValuesAndBandsForAuthenticatedCustomer() throws Exception {
        var result =
                TaxCalculationResultDTO.builder()
                        .originalGainAmount(new BigDecimal("32000000"))
                        .weightedGain(new BigDecimal("27800000"))
                        .weightedSell(new BigDecimal("43000000"))
                        .weightedExternalAmount(new BigDecimal("11000000"))
                        .adjustRatio(new BigDecimal("0.7442"))
                        .finalDeduction(new BigDecimal("20690000"))
                        .finalTax(new BigDecimal("1939000"))
                        .build();
        var band =
                TaxPeriodBreakdownDTO.builder()
                        .validFrom(LocalDate.of(2026, 6, 1))
                        .validTo(LocalDate.of(2026, 7, 31))
                        .weight(new BigDecimal("0.8"))
                        .sellAmount(new BigDecimal("10000000"))
                        .gainAmount(new BigDecimal("6000000"))
                        .build();
        when(taxCalculationService.getCustomerTaxBasis(CUSTOMER_ID))
                .thenReturn(
                        CustomerTaxBasisResponseDTO.of(
                                1L,
                                BenefitType.POSSIBLE,
                                result,
                                List.of(band),
                                new BigDecimal("2500000"),
                                new BigDecimal("6490000"),
                                LocalDateTime.of(2026, 11, 4, 9, 0)));

        mockMvc.perform(
                        get("/api/customer/tax/basis")
                                .param("accountId", "999")
                                .with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weightedSell").value(43000000))
                .andExpect(jsonPath("$.data.preAdjustDeduction").value(27800000))
                .andExpect(jsonPath("$.data.weightedNetPurchase").value(11000000))
                .andExpect(jsonPath("$.data.adjustRatio").value(0.7442))
                .andExpect(jsonPath("$.data.finalDeduction").value(20690000))
                .andExpect(jsonPath("$.data.totalGain").value(32000000))
                .andExpect(jsonPath("$.data.basicDeduction").value(2500000))
                .andExpect(jsonPath("$.data.reliefAmount").value(4551000))
                .andExpect(jsonPath("$.data.benefit").value("POSSIBLE"))
                .andExpect(jsonPath("$.data.reliefExcluded").value(false))
                .andExpect(jsonPath("$.data.bands[0].validFrom").value("2026-06-01"))
                .andExpect(jsonPath("$.data.bands[0].fromMonth").value(6))
                .andExpect(jsonPath("$.data.bands[0].toMonth").value(7))
                .andExpect(jsonPath("$.data.bands[0].weightRate").value(0.8))
                .andExpect(jsonPath("$.data.bands[0].sellAmount").value(10000000))
                .andExpect(jsonPath("$.data.bands[0].gain").value(6000000));
        verify(taxCalculationService).getCustomerTaxBasis(CUSTOMER_ID);
    }
}
