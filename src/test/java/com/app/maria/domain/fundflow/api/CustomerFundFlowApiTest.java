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
import com.app.maria.domain.fundflow.dto.FundFlowTransactionDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionsDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalLotDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalScheduleDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowTransactionsResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowWithdrawalScheduleResponseDTO;
import com.app.maria.domain.fundflow.service.CustomerFundFlowService;
import com.app.maria.domain.fundflow.type.FundFlowTransactionType;
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

    @Test
    @DisplayName("거래 내역: 미인증이면 401이고 서비스는 호출되지 않는다")
    void transactionsReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/customer/fund-flow/transactions"))
                .andExpect(status().isUnauthorized());

        verify(customerFundFlowService, never()).getTransactions(CUSTOMER_ID);
    }

    @Test
    @DisplayName("거래 내역: 로그인한 고객은 경로·파라미터 없이 본인 customerId로 조회한다")
    void transactionsUsesPrincipalCustomerId() throws Exception {
        FundFlowTransactionRowDTO detail =
                FundFlowTransactionRowDTO.builder()
                        .productName("삼성전자")
                        .qty(new BigDecimal("3"))
                        .amount(new BigDecimal("300000"))
                        .feeAmount(new BigDecimal("500"))
                        .taxAmount(BigDecimal.ZERO)
                        .build();
        FundFlowTransactionDTO item =
                FundFlowTransactionDTO.builder()
                        .date(LocalDate.of(2026, 10, 23))
                        .type(FundFlowTransactionType.DOMESTIC_BUY)
                        .amount(new BigDecimal("-31540444"))
                        .cashAfter(new BigDecimal("15475711"))
                        .title("삼성전자 외 1")
                        .details(List.of(detail))
                        .build();
        when(customerFundFlowService.getTransactions(CUSTOMER_ID))
                .thenReturn(
                        new FundFlowTransactionsResponseDTO(
                                FundFlowTransactionsDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .hasAccount(true)
                                        .items(List.of(item))
                                        .build()));

        mockMvc.perform(get("/api/customer/fund-flow/transactions").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 자금 이동 내역 조회 성공"))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-11-17"))
                .andExpect(jsonPath("$.data.hasAccount").value(true))
                .andExpect(jsonPath("$.data.items[0].date").value("2026-10-23"))
                .andExpect(jsonPath("$.data.items[0].type").value("DOMESTIC_BUY"))
                .andExpect(jsonPath("$.data.items[0].amount").value(-31540444))
                .andExpect(jsonPath("$.data.items[0].cashAfter").value(15475711))
                .andExpect(jsonPath("$.data.items[0].title").value("삼성전자 외 1"))
                .andExpect(jsonPath("$.data.items[0].details[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.data.items[0].details[0].feeAmount").value(500))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("인출 일정: 미인증이면 401이고 서비스는 호출되지 않는다")
    void withdrawalScheduleReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/customer/fund-flow/withdrawal-schedule"))
                .andExpect(status().isUnauthorized());

        verify(customerFundFlowService, never()).getWithdrawalSchedule(CUSTOMER_ID);
    }

    @Test
    @DisplayName("인출 일정: 로그인한 고객은 경로·파라미터 없이 본인 customerId로 조회한다")
    void withdrawalScheduleUsesPrincipalCustomerId() throws Exception {
        FundFlowWithdrawalLotDTO lot =
                FundFlowWithdrawalLotDTO.builder()
                        .paidDate(LocalDate.of(2026, 4, 28))
                        .maturityDate(LocalDate.of(2027, 4, 28))
                        .paidAmount(new BigDecimal("30000000"))
                        .withdrawnAmount(new BigDecimal("2000000"))
                        .remainingAmount(new BigDecimal("28000000"))
                        .matured(false)
                        .build();
        when(customerFundFlowService.getWithdrawalSchedule(CUSTOMER_ID))
                .thenReturn(
                        new FundFlowWithdrawalScheduleResponseDTO(
                                FundFlowWithdrawalScheduleDTO.builder()
                                        .businessDate(LocalDate.of(2026, 11, 18))
                                        .hasAccount(true)
                                        .lots(List.of(lot))
                                        .build()));

        mockMvc.perform(get("/api/customer/fund-flow/withdrawal-schedule").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 인출 가능 일정 조회 성공"))
                .andExpect(jsonPath("$.data.businessDate").value("2026-11-18"))
                .andExpect(jsonPath("$.data.hasAccount").value(true))
                .andExpect(jsonPath("$.data.lots[0].paidDate").value("2026-04-28"))
                .andExpect(jsonPath("$.data.lots[0].maturityDate").value("2027-04-28"))
                .andExpect(jsonPath("$.data.lots[0].paidAmount").value(30000000))
                .andExpect(jsonPath("$.data.lots[0].withdrawnAmount").value(2000000))
                .andExpect(jsonPath("$.data.lots[0].remainingAmount").value(28000000))
                .andExpect(jsonPath("$.data.lots[0].matured").value(false))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }
}
