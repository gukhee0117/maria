package com.app.maria.domain.fundflow.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.fundflow.dto.FundFlowAllocationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthlyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;
import com.app.maria.domain.fundflow.service.AdminFundFlowService;
import com.app.maria.domain.fundflow.type.ReliefScope;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminFundFlowApi.class)
@Import(SecurityConfig.class)
class AdminFundFlowAnalyticsApiTest {

    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 11, 17);

    @Autowired MockMvc mockMvc;

    @MockitoBean AdminFundFlowService adminFundFlowService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("개요: 200과 기준일, 재투자율, 분모 문구를 반환한다")
    @WithMockUser(roles = "VIEWER")
    void overview() throws Exception {
        when(adminFundFlowService.getOverview())
                .thenReturn(
                        new FundFlowOverviewResponseDTO(
                                FundFlowOverviewDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .openedAccounts(75046L)
                                        .inflowTotal(new BigDecimal("602660000000"))
                                        .domesticBuyTotal(new BigDecimal("426020000000"))
                                        .reinvestRatio(new BigDecimal("0.7069"))
                                        .netInvestRatio(new BigDecimal("0.6333"))
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("자금 환류 개요 조회 성공"))
                .andExpect(jsonPath("$.data.openedAccounts").value(75046))
                .andExpect(jsonPath("$.data.reinvestRatio").value(0.7069))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("누수: 200과 분류 목록을 반환한다")
    @WithMockUser(roles = "REVIEWER")
    void leakage() throws Exception {
        when(adminFundFlowService.getLeakage())
                .thenReturn(
                        new FundFlowLeakageResponseDTO(
                                FundFlowLeakageDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .total(0L)
                                        .categories(List.of())
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/leakage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("월별: fromMonth/toMonth(yyyy-MM)를 요청 DTO로 바인딩한다")
    @WithMockUser(roles = "VIEWER")
    void monthlyBindsMonthRange() throws Exception {
        when(adminFundFlowService.getMonthly(any()))
                .thenReturn(
                        new FundFlowMonthlyResponseDTO(
                                FundFlowMonthlyDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .months(List.of())
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/monthly?fromMonth=2026-04&toMonth=2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.months").isEmpty());

        ArgumentCaptor<FundFlowMonthlyRequestDTO> captor =
                ArgumentCaptor.forClass(FundFlowMonthlyRequestDTO.class);
        verify(adminFundFlowService).getMonthly(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getFromMonth())
                .isEqualTo(YearMonth.of(2026, 4));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getToMonth())
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    @DisplayName("세그먼트: 200을 반환한다")
    @WithMockUser(roles = "SETTLEMENT")
    void segments() throws Exception {
        when(adminFundFlowService.getSegments())
                .thenReturn(
                        new FundFlowSegmentResponseDTO(
                                FundFlowSegmentDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .cells(List.of())
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/segments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cells").isEmpty());
    }

    @Test
    @DisplayName("감면액: scope 생략 시 ALL, POSITIVE를 주면 POSITIVE로 바인딩한다")
    @WithMockUser(roles = "ADMIN")
    void reliefBindsScope() throws Exception {
        when(adminFundFlowService.getRelief(any()))
                .thenReturn(
                        new FundFlowReliefResponseDTO(
                                FundFlowReliefDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .scope("ALL")
                                        .accounts(1L)
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/relief")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/fund-flow/relief?scope=POSITIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definitionVersion").value("draft-1"));

        ArgumentCaptor<FundFlowReliefRequestDTO> captor =
                ArgumentCaptor.forClass(FundFlowReliefRequestDTO.class);
        verify(adminFundFlowService, org.mockito.Mockito.times(2)).getRelief(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getAllValues())
                .extracting(FundFlowReliefRequestDTO::getScope)
                .containsExactly(ReliefScope.ALL, ReliefScope.POSITIVE);
    }

    @Test
    @DisplayName("감면액: 정의되지 않은 scope면 400이다")
    @WithMockUser(roles = "ADMIN")
    void reliefRejectsUnknownScope() throws Exception {
        mockMvc.perform(get("/api/admin/fund-flow/relief?scope=NONE"))
                .andExpect(status().isBadRequest());

        verify(adminFundFlowService, never()).getRelief(any());
    }

    @Test
    @DisplayName("운용처: 200과 매입금액 기준 표기를 반환한다")
    @WithMockUser(roles = "VIEWER")
    void allocation() throws Exception {
        when(adminFundFlowService.getAllocation())
                .thenReturn(
                        new FundFlowAllocationResponseDTO(
                                FundFlowAllocationDTO.builder()
                                        .asOfDate(AS_OF_DATE)
                                        .totalCost(BigDecimal.ZERO)
                                        .buckets(List.of())
                                        .topNetBuy(List.of())
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/allocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valueBasis").value("PURCHASE_COST"));
    }

    @Test
    @DisplayName("인증되지 않은 요청이면 새 엔드포인트도 401이고 서비스는 호출되지 않는다")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/fund-flow/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/fund-flow/leakage")).andExpect(status().isUnauthorized());

        verify(adminFundFlowService, never()).getOverview();
        verify(adminFundFlowService, never()).getLeakage();
    }
}
