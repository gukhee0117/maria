package com.app.maria.domain.asset.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;
import com.app.maria.domain.asset.service.AssetBriefingService;
import com.app.maria.domain.asset.vo.AssetBriefingVO;
import com.app.maria.domain.asset.vo.AssetBriefingVO.MonthlyPurchase;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(
        value = CustomerAssetBriefingApi.class,
        properties = "spring.config.location=optional:classpath:/application-webmvctest.yml")
@Import(CustomerSecurityConfig.class)
class CustomerAssetBriefingApiTest {
    private static final String URL = "/api/customer/assets/briefing";
    @Autowired MockMvc mockMvc;
    @MockitoBean AssetBriefingService service;

    private RequestPostProcessor auth(String role) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "customer@test.com", "테스트고객"),
                        null,
                        List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void unauthenticatedRequestIsRejectedWithoutReadingAssets() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void adminRoleCannotReadCustomerAssets() throws Exception {
        mockMvc.perform(get(URL).with(auth("ROLE_ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void usesAuthenticatedCustomerNotClientSuppliedIdsAndPreservesUnknownValues() throws Exception {
        var response =
                new CustomerAssetBriefingResponseDTO(
                        AssetBriefingVO.builder()
                                .accountId(11L)
                                .cashAmount(new BigDecimal("12345"))
                                .investmentCostAmount(new BigDecimal("1980.75"))
                                .cumulativeInflowAmount(new BigDecimal("5000"))
                                .investmentRatio(new BigDecimal("39.62"))
                                .monthlyPurchases(
                                        List.of(
                                                new MonthlyPurchase(
                                                        "2026-03", new BigDecimal("700"))))
                                .unavailableFields(
                                        List.of(
                                                "domesticValuationAmount",
                                                "valuationProfit",
                                                "valuationReturnRate",
                                                "packageDeviation"))
                                .calculatedAt(LocalDateTime.of(2026, 11, 2, 10, 30))
                                .build());
        when(service.getBriefing(7L)).thenReturn(response);
        mockMvc.perform(
                        get(URL).param("customerId", "999")
                                .param("accountId", "999")
                                .with(auth("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("자산 브리핑 조회 성공"))
                .andExpect(jsonPath("$.data.accountId").value(11))
                .andExpect(jsonPath("$.data.cashAmount").value(12345))
                .andExpect(jsonPath("$.data.investmentCostAmount").value(1980.75))
                .andExpect(jsonPath("$.data.cumulativeInflowAmount").value(5000))
                .andExpect(jsonPath("$.data.investmentRatio").value(39.62))
                .andExpect(jsonPath("$.data.domesticValuationAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.valuationProfit").value(nullValue()))
                .andExpect(jsonPath("$.data.valuationReturnRate").value(nullValue()))
                .andExpect(jsonPath("$.data.packageDeviation").value(nullValue()))
                .andExpect(jsonPath("$.data.monthlyPurchases[0].month").value("2026-03"))
                .andExpect(jsonPath("$.data.monthlyPurchases[0].buyAmount").value(700))
                .andExpect(jsonPath("$.data.unavailableFields.length()").value(4))
                .andExpect(jsonPath("$.data.calculatedAt").value("2026-11-02T10:30:00"));
        verify(service).getBriefing(7L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void accountNotFoundUsesCommonErrorResponse() throws Exception {
        when(service.getBriefing(7L)).thenThrow(new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        mockMvc.perform(get(URL).with(auth("ROLE_CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void serializesValuationWithItsPriceTimestampsWhenPricesAreAvailable() throws Exception {
        when(service.getBriefing(7L))
                .thenReturn(
                        new CustomerAssetBriefingResponseDTO(
                                AssetBriefingVO.builder()
                                        .accountId(11L)
                                        .domesticValuationAmount(new BigDecimal("920"))
                                        .valuationProfit(new BigDecimal("120"))
                                        .valuationReturnRate(new BigDecimal("15.00"))
                                        .valuationPriceFrom(LocalDateTime.of(2026, 11, 2, 10, 28))
                                        .valuationPriceTo(LocalDateTime.of(2026, 11, 2, 10, 29))
                                        .unavailableFields(List.of("packageDeviation"))
                                        .build()));
        mockMvc.perform(get(URL).with(auth("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domesticValuationAmount").value(920))
                .andExpect(jsonPath("$.data.valuationProfit").value(120))
                .andExpect(jsonPath("$.data.valuationReturnRate").value(15))
                .andExpect(jsonPath("$.data.valuationPriceFrom").value("2026-11-02T10:28:00"))
                .andExpect(jsonPath("$.data.valuationPriceTo").value("2026-11-02T10:29:00"));
    }
}
