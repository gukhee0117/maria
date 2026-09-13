package com.app.maria.domain.statistics.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.statistics.dto.AccountBenefitStatDTO;
import com.app.maria.domain.statistics.dto.AgeInvestmentStatDTO;
import com.app.maria.domain.statistics.dto.FxExchangeStatDTO;
import com.app.maria.domain.statistics.dto.ReliefRateStatDTO;
import com.app.maria.domain.statistics.service.StatisticsService;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatisticsApi.class)
@Import(SecurityConfig.class)
class StatisticsApiTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean StatisticsService statisticsService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("나이대별 투자 현황을 조회하면 200과 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getAgeInvestmentStatsReturns200WithContent() throws Exception {
        AgeInvestmentStatDTO stat =
                AgeInvestmentStatDTO.builder()
                        .ageGroup("30대")
                        .purchaseAmount(new BigDecimal("1000000"))
                        .purchaseCount(2)
                        .build();
        when(statisticsService.getAgeInvestmentStats(any())).thenReturn(List.of(stat));

        mockMvc.perform(get("/api/admin/statistics/age-investment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("나이대별 투자 현황 조회 성공"))
                .andExpect(jsonPath("$.data[0].ageGroup").value("30대"))
                .andExpect(jsonPath("$.data[0].purchaseCount").value(2));
    }

    @Test
    @DisplayName("종목별 매수 현황 조회 시 검색어(keyword)/종목/기간 파라미터를 서비스에 그대로 전달한다")
    @WithMockUser(roles = "VIEWER")
    void getProductPurchaseStatsPassesQueryParamsToService() throws Exception {
        when(statisticsService.getProductPurchaseStats(any())).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/admin/statistics/product-purchase")
                                .param("keyword", "홍길동")
                                .param("productName", "삼성전자")
                                .param("startDate", "2026-08-01")
                                .param("endDate", "2026-08-10"))
                .andExpect(status().isOk());

        verify(statisticsService)
                .getProductPurchaseStats(
                        argThat(
                                r ->
                                        "홍길동".equals(r.getKeyword())
                                                && "삼성전자".equals(r.getProductName())
                                                && LocalDate.of(2026, 8, 1).equals(r.getStartDate())
                                                && LocalDate.of(2026, 8, 10)
                                                        .equals(r.getEndDate())));
    }

    @Test
    @DisplayName("외화유입 · 원화환전 통계를 조회하면 200과 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getFxExchangeStatsReturns200WithContent() throws Exception {
        FxExchangeStatDTO stat =
                FxExchangeStatDTO.builder()
                        .statDate(LocalDate.of(2026, 8, 1))
                        .provisionalAmount(new BigDecimal("500000"))
                        .finalAmount(new BigDecimal("495000"))
                        .build();
        when(statisticsService.getFxExchangeStats(any())).thenReturn(List.of(stat));

        mockMvc.perform(get("/api/admin/statistics/fx-exchange"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].statDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data[0].finalAmount").value(495000));
    }

    @Test
    @DisplayName("세제혜택 상태 분포를 조회하면 200과 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getAccountBenefitStatsReturns200WithContent() throws Exception {
        AccountBenefitStatDTO stat =
                AccountBenefitStatDTO.builder().benefit("POSSIBLE").accountCount(10).build();
        when(statisticsService.getAccountBenefitStats(any())).thenReturn(List.of(stat));

        mockMvc.perform(get("/api/admin/statistics/account-benefit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].benefit").value("POSSIBLE"))
                .andExpect(jsonPath("$.data[0].accountCount").value(10));
    }

    @Test
    @DisplayName("감면율 구간별 매도금액 분포를 조회하면 200과 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getReliefRateStatsReturns200WithContent() throws Exception {
        ReliefRateStatDTO stat =
                ReliefRateStatDTO.builder()
                        .periodLabel("1~5월 (100%)")
                        .sellAmount(new BigDecimal("3000000"))
                        .build();
        when(statisticsService.getReliefRateStats(any())).thenReturn(List.of(stat));

        mockMvc.perform(get("/api/admin/statistics/relief-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].periodLabel").value("1~5월 (100%)"));
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getAgeInvestmentStatsReturns200WithEmptyListWhenNoData() throws Exception {
        when(statisticsService.getAgeInvestmentStats(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/statistics/age-investment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("인증되지 않은 요청이면 401을 반환하고 서비스는 호출되지 않는다")
    void getAgeInvestmentStatsReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/statistics/age-investment")).andExpect(status().isUnauthorized());

        verify(statisticsService, org.mockito.Mockito.never()).getAgeInvestmentStats(any());
    }
}
