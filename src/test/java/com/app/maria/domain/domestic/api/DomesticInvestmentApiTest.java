package com.app.maria.domain.domestic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.domestic.dto.DomesticAccountDetailDTO;
import com.app.maria.domain.domestic.dto.DomesticCashHeavyAccountDTO;
import com.app.maria.domain.domestic.dto.DomesticCashHeavyPageDTO;
import com.app.maria.domain.domestic.dto.DomesticHoldingDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentListDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentPageDTO;
import com.app.maria.domain.domestic.dto.request.DomesticInvestmentSearchRequestDTO;
import com.app.maria.domain.domestic.dto.response.DomesticAccountLiteResponseDTO;
import com.app.maria.domain.domestic.dto.response.DomesticCashHeavyPageResponseDTO;
import com.app.maria.domain.domestic.dto.response.DomesticInvestmentSummaryResponseDTO;
import com.app.maria.domain.domestic.dto.response.DomesticRestrictedHoldingResponseDTO;
import com.app.maria.domain.domestic.dto.response.DomesticUnpurchasableHoldingResponseDTO;
import com.app.maria.domain.domestic.exception.DomesticInvestmentNotFoundException;
import com.app.maria.domain.domestic.service.DomesticInvestmentService;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DomesticInvestmentApi.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "VIEWER")
class DomesticInvestmentApiTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DomesticInvestmentService domesticInvestmentService;

    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("목록 조회 성공 시 페이지 결과를 JSON으로 반환한다")
    void getInvestmentsReturnsPagedResultAsJson() throws Exception {
        DomesticInvestmentListDTO item =
                DomesticInvestmentListDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .cashAmount(BigDecimal.valueOf(500000))
                        .holdingCount(2)
                        .hasRestrictedHolding(false)
                        .build();
        DomesticInvestmentPageDTO page =
                DomesticInvestmentPageDTO.builder()
                        .content(List.of(item))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .build();
        when(domesticInvestmentService.getInvestments(
                        any(DomesticInvestmentSearchRequestDTO.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/domestic-investments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("국내투자 현황 목록 조회 성공"))
                .andExpect(jsonPath("$.data.content[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.data.content[0].cashAmount").value(500000))
                .andExpect(jsonPath("$.data.content[0].holdingCount").value(2));
    }

    @Test
    @DisplayName(
            "목록 조회 시 hasRestrictedHolding·hasUnpurchasableHolding·hasRecentBuy·recentBuyDays 쿼리파라미터를 서비스에 그대로 전달한다")
    void getInvestmentsPassesNewFilterQueryParamsToService() throws Exception {
        when(domesticInvestmentService.getInvestments(
                        any(DomesticInvestmentSearchRequestDTO.class)))
                .thenReturn(
                        DomesticInvestmentPageDTO.builder()
                                .content(List.of())
                                .page(0)
                                .size(20)
                                .totalElements(0)
                                .totalPages(0)
                                .build());

        mockMvc.perform(
                        get("/api/admin/domestic-investments")
                                .param("hasRestrictedHolding", "true")
                                .param("hasUnpurchasableHolding", "false")
                                .param("hasRecentBuy", "true")
                                .param("recentBuyDays", "14"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(DomesticInvestmentSearchRequestDTO.class);
        verify(domesticInvestmentService).getInvestments(captor.capture());
        assertThat(captor.getValue().getHasRestrictedHolding()).isTrue();
        assertThat(captor.getValue().getHasUnpurchasableHolding()).isFalse();
        assertThat(captor.getValue().getHasRecentBuy()).isTrue();
        assertThat(captor.getValue().getRecentBuyDays()).isEqualTo(14);
    }

    @ParameterizedTest(name = "{0}은 목록을 조회할 수 있다")
    @ValueSource(strings = {"ADMIN", "SETTLEMENT", "REVIEWER", "VIEWER"})
    void getInvestmentsAllowsAllRoles(String role) throws Exception {
        when(domesticInvestmentService.getInvestments(
                        any(DomesticInvestmentSearchRequestDTO.class)))
                .thenReturn(
                        DomesticInvestmentPageDTO.builder()
                                .content(List.of())
                                .page(0)
                                .size(20)
                                .totalElements(0)
                                .totalPages(0)
                                .build());

        mockMvc.perform(get("/api/admin/domestic-investments").with(user("tester").roles(role)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증되지 않은 요청은 목록 조회를 거부한다")
    @WithAnonymousUser
    void getInvestmentsRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/domestic-investments")).andExpect(status().isUnauthorized());

        verify(domesticInvestmentService, never())
                .getInvestments(any(DomesticInvestmentSearchRequestDTO.class));
    }

    @Test
    @DisplayName("계좌 상세 조회 성공 시 예탁금·보유종목·매매내역을 JSON으로 반환한다")
    void getAccountDetailReturnsAccountDetailAsJson() throws Exception {
        DomesticHoldingDTO holding =
                DomesticHoldingDTO.builder()
                        .ticker("005930")
                        .name("삼성전자")
                        .qty(BigDecimal.valueOf(10))
                        .currentlyPurchasable(true)
                        .build();
        DomesticAccountDetailDTO detail =
                DomesticAccountDetailDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .cashAmount(BigDecimal.valueOf(300000))
                        .holdings(List.of(holding))
                        .tradeHistory(List.of())
                        .build();
        when(domesticInvestmentService.getAccountDetail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/admin/domestic-investments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("국내투자 현황 상세 조회 성공"))
                .andExpect(jsonPath("$.data.customerName").value("홍길동"))
                .andExpect(jsonPath("$.data.holdings[0].ticker").value("005930"))
                .andExpect(jsonPath("$.data.holdings[0].currentlyPurchasable").value(true));
    }

    @Test
    @DisplayName("존재하지 않는 계좌면 404를 반환한다")
    void getAccountDetailReturnsNotFoundWhenAccountMissing() throws Exception {
        when(domesticInvestmentService.getAccountDetail(999L))
                .thenThrow(new DomesticInvestmentNotFoundException("계좌를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/admin/domestic-investments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("계좌를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("accountId가 0 이하면 400을 반환하고 서비스는 호출하지 않는다")
    void getAccountDetailRejectsNonPositiveAccountId() throws Exception {
        mockMvc.perform(get("/api/admin/domestic-investments/0")).andExpect(status().isBadRequest());

        verify(domesticInvestmentService, never()).getAccountDetail(anyLong());
    }

    @Test
    @DisplayName("요약 조회 성공 시 통계를 JSON으로 반환하고, days 기본값 7을 서비스에 전달한다")
    void getSummaryReturnsStatsAsJsonWithDefaultDays() throws Exception {
        DomesticInvestmentSummaryResponseDTO summary =
                DomesticInvestmentSummaryResponseDTO.builder()
                        .totalAccountCount(20)
                        .restrictedAccountCount(3)
                        .unpurchasableHoldingCount(2)
                        .totalCashAmount(BigDecimal.valueOf(10_300_000))
                        .domesticStockAmount(BigDecimal.valueOf(16_000_000))
                        .domesticFundAmount(BigDecimal.valueOf(1_880_000))
                        .stockHoldingAccountCount(9)
                        .fundHoldingAccountCount(3)
                        .recentBuyAccountCount(3)
                        .noRecentBuyAccountCount(17)
                        .build();
        when(domesticInvestmentService.getSummary(7)).thenReturn(summary);

        mockMvc.perform(get("/api/admin/domestic-investments/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("국내투자 현황 요약 조회 성공"))
                .andExpect(jsonPath("$.data.totalAccountCount").value(20))
                .andExpect(jsonPath("$.data.recentBuyAccountCount").value(3));

        verify(domesticInvestmentService).getSummary(7);
    }

    @Test
    @DisplayName("요약 조회 시 days 쿼리파라미터를 서비스에 그대로 전달한다")
    void getSummaryPassesDaysQueryParamToService() throws Exception {
        when(domesticInvestmentService.getSummary(30))
                .thenReturn(DomesticInvestmentSummaryResponseDTO.builder().build());

        mockMvc.perform(get("/api/admin/domestic-investments/summary").param("days", "30"))
                .andExpect(status().isOk());

        verify(domesticInvestmentService).getSummary(30);
    }

    @Test
    @DisplayName("거래제한·정지 종목 목록 조회 성공 시 JSON으로 반환한다")
    void getRestrictedHoldingsReturnsListAsJson() throws Exception {
        DomesticRestrictedHoldingResponseDTO holding =
                DomesticRestrictedHoldingResponseDTO.builder()
                        .customerName("홍길동")
                        .accountNo("1234567890")
                        .productName("삼성바이오로직스")
                        .ticker("207940")
                        .status(DomesticStockStatus.TRADE_SUSPENDED)
                        .build();
        when(domesticInvestmentService.getRestrictedHoldings()).thenReturn(List.of(holding));

        mockMvc.perform(get("/api/admin/domestic-investments/restricted-holdings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("거래제한·정지 종목 목록 조회 성공"))
                .andExpect(jsonPath("$.data[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.data[0].status").value("TRADE_SUSPENDED"));
    }

    @Test
    @DisplayName("예탁금 비중 높은 계좌 목록 조회 성공 시 페이지 JSON으로 반환하고 기본 page·size를 전달한다")
    void getCashHeavyAccountsReturnsPagedListAsJson() throws Exception {
        DomesticCashHeavyAccountDTO account =
                DomesticCashHeavyAccountDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .cashAmount(BigDecimal.valueOf(600_000))
                        .investedAmount(BigDecimal.valueOf(400_000))
                        .build();
        DomesticCashHeavyPageResponseDTO page =
                new DomesticCashHeavyPageResponseDTO(
                        DomesticCashHeavyPageDTO.builder()
                                .content(List.of(account))
                                .page(0)
                                .size(20)
                                .totalElements(1)
                                .totalPages(1)
                                .build());
        when(domesticInvestmentService.getCashHeavyAccounts(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/domestic-investments/cash-heavy-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("예탁금 비중 높은 계좌 목록 조회 성공"))
                .andExpect(jsonPath("$.data.content[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(domesticInvestmentService).getCashHeavyAccounts(0, 20);
    }

    @Test
    @DisplayName("매수불가 종목 목록 조회 성공 시 JSON으로 반환한다")
    void getUnpurchasableHoldingsReturnsListAsJson() throws Exception {
        DomesticUnpurchasableHoldingResponseDTO holding =
                DomesticUnpurchasableHoldingResponseDTO.builder()
                        .customerName("김철수")
                        .accountNo("2222222222")
                        .productName("TIGER 미국S&P500")
                        .ticker("360750")
                        .build();
        when(domesticInvestmentService.getUnpurchasableHoldings()).thenReturn(List.of(holding));

        mockMvc.perform(get("/api/admin/domestic-investments/unpurchasable-holdings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("매수불가 종목 목록 조회 성공"))
                .andExpect(jsonPath("$.data[0].customerName").value("김철수"));
    }

    @Test
    @DisplayName("최근 매수 계좌 목록 조회 시 days·hasRecentBuy 쿼리파라미터를 서비스에 그대로 전달한다")
    void getRecentBuyAccountsPassesQueryParamsToService() throws Exception {
        DomesticAccountLiteResponseDTO account =
                DomesticAccountLiteResponseDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .build();
        when(domesticInvestmentService.getRecentBuyAccounts(14, true)).thenReturn(List.of(account));

        mockMvc.perform(
                        get("/api/admin/domestic-investments/recent-buy-accounts")
                                .param("days", "14")
                                .param("hasRecentBuy", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("최근 매수 계좌 목록 조회 성공"))
                .andExpect(jsonPath("$.data[0].customerName").value("홍길동"));

        verify(domesticInvestmentService).getRecentBuyAccounts(14, true);
    }
}
