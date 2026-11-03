package com.app.maria.domain.fundflow.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.fundflow.dto.FundFlowCustomerAllocationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerSummaryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthlyDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.service.CustomerFundFlowService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
        value = CustomerFundFlowApi.class,
        properties = "spring.config.location=optional:classpath:/application-webmvctest.yml")
@Import(CustomerSecurityConfig.class)
class CustomerFundFlowApiTest {

    private static final Long CUSTOMER_ID = 7L;
    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 11, 17);
    private static final CustomerPrincipal PRINCIPAL =
            new CustomerPrincipal(CUSTOMER_ID, "customer@test.com", "테스트고객");

    @Autowired MockMvc mockMvc;

    @MockitoBean CustomerFundFlowService customerFundFlowService;

    private static RequestPostProcessor customerAuth() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return authentication(authentication);
    }

    @Test
    @DisplayName("미인증 고객은 요약을 조회할 수 없고 서비스는 호출되지 않는다")
    void summaryReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/customer/fund-flow/summary"))
                .andExpect(status().isUnauthorized());

        verify(customerFundFlowService, never()).getSummary(CUSTOMER_ID);
    }

    @Test
    @DisplayName("로그인한 고객은 본인 customerId로 요약을 조회한다")
    void summaryUsesPrincipalCustomerId() throws Exception {
        when(customerFundFlowService.getSummary(CUSTOMER_ID))
                .thenReturn(
                        new FundFlowCustomerSummaryResponseDTO(
                                FundFlowCustomerSummaryDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .hasAccount(true)
                                        .soldForeignAmount(new BigDecimal("15000"))
                                        .krwInflowAmount(new BigDecimal("14000"))
                                        .domesticBuyAmount(new BigDecimal("7000"))
                                        .cashBalance(new BigDecimal("12345"))
                                        .reinvestRatio(new BigDecimal("0.5000"))
                                        .build()));

        mockMvc.perform(get("/api/customer/fund-flow/summary").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 자금 흐름 요약 조회 성공"))
                .andExpect(jsonPath("$.data.hasAccount").value(true))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-11-17"))
                .andExpect(jsonPath("$.data.reinvestRatio").value(0.5))
                .andExpect(jsonPath("$.data.message").value("해외주식을 판 돈의 50%를 다시 국내에 투자했어요"))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("로그인한 고객은 본인 월별 흐름을 조회한다")
    void monthlyUsesPrincipalCustomerId() throws Exception {
        when(customerFundFlowService.getMonthly(CUSTOMER_ID))
                .thenReturn(
                        new FundFlowMonthlyResponseDTO(
                                FundFlowMonthlyDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .months(List.of())
                                        .build()));

        mockMvc.perform(get("/api/customer/fund-flow/monthly").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 월별 자금 흐름 조회 성공"))
                .andExpect(jsonPath("$.data.months").isEmpty());
    }

    @Test
    @DisplayName("로그인한 고객은 본인 운용처를 조회하고, 미인증이면 401이다")
    void allocationUsesPrincipalCustomerId() throws Exception {
        mockMvc.perform(get("/api/customer/fund-flow/allocation"))
                .andExpect(status().isUnauthorized());

        when(customerFundFlowService.getAllocation(CUSTOMER_ID))
                .thenReturn(
                        new FundFlowCustomerAllocationResponseDTO(
                                FundFlowCustomerAllocationDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .hasAccount(true)
                                        .totalCost(new BigDecimal("10000"))
                                        .build()));

        mockMvc.perform(get("/api/customer/fund-flow/allocation").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valueBasis").value("PURCHASE_COST"))
                .andExpect(jsonPath("$.data.totalValue").value(10000))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("로그인한 고객은 본인 예상 세금 혜택을 조회하고, 계좌가 없어도 200이다")
    void reliefReturns200EvenWithoutAccount() throws Exception {
        when(customerFundFlowService.getRelief(CUSTOMER_ID))
                .thenReturn(
                        new FundFlowCustomerReliefResponseDTO(
                                FundFlowCustomerReliefDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .hasAccount(false)
                                        .build()));

        mockMvc.perform(get("/api/customer/fund-flow/relief").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasAccount").value(false))
                .andExpect(jsonPath("$.data.notice").isNotEmpty());
    }
}
