package com.app.maria.domain.targetproduct.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.targetproduct.dto.TargetProductJudgementListDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementPageDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductSummaryDTO;
import com.app.maria.domain.targetproduct.dto.request.TargetProductSearchRequestDTO;
import com.app.maria.domain.targetproduct.service.TargetProductService;
import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TargetProductApi.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "VIEWER")
class TargetProductApiTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TargetProductService targetProductService;

    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    private static TargetProductJudgementListDTO judgementListDto() {
        return TargetProductJudgementListDTO.builder()
                .judgementId(1L)
                .customerName("홍길동")
                .stockType(StockType.FOREIGN_STOCK)
                .ticker("AAPL")
                .isTarget(true)
                .tradeType(TradeType.BUY)
                .amount(new BigDecimal("1000000"))
                .netBuyAmount(new BigDecimal("1000000"))
                .tradeDate(LocalDate.of(2026, 3, 5))
                .judgedAt(LocalDateTime.of(2026, 8, 7, 3, 0))
                .build();
    }

    @Test
    void getJudgementsReturnsPagedResultAsJsonWithDefaultPageAndSize() throws Exception {
        TargetProductJudgementPageDTO page =
                TargetProductJudgementPageDTO.builder()
                        .content(List.of(judgementListDto()))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .build();
        when(targetProductService.getJudgements(any(TargetProductSearchRequestDTO.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/target-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.data.content[0].stockType").value("FOREIGN_STOCK"))
                .andExpect(jsonPath("$.data.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void getJudgementsReturnsForeignStockRatioAndInceptionDateForFund() throws Exception {
        TargetProductJudgementListDTO fundJudgement =
                TargetProductJudgementListDTO.builder()
                        .judgementId(2L)
                        .customerName("김철수")
                        .stockType(StockType.FUND)
                        .fundName("미래에셋글로벌펀드")
                        .isTarget(false)
                        .foreignStockRatio(new BigDecimal("45.00"))
                        .inceptionDate(LocalDate.of(2026, 7, 20))
                        .tradeType(TradeType.BUY)
                        .amount(new BigDecimal("500000"))
                        .netBuyAmount(new BigDecimal("500000"))
                        .tradeDate(LocalDate.of(2026, 8, 1))
                        .judgedAt(LocalDateTime.of(2026, 8, 7, 3, 0))
                        .build();
        TargetProductJudgementPageDTO page =
                TargetProductJudgementPageDTO.builder()
                        .content(List.of(fundJudgement))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .build();
        when(targetProductService.getJudgements(any(TargetProductSearchRequestDTO.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/target-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].foreignStockRatio").value(45.00))
                .andExpect(jsonPath("$.data.content[0].inceptionDate").value("2026-07-20"))
                .andExpect(jsonPath("$.data.content[0].isTarget").value(false));
    }

    @Test
    void getJudgementsPassesPageAndSizeQueryParamsToService() throws Exception {
        TargetProductJudgementPageDTO page =
                TargetProductJudgementPageDTO.builder()
                        .content(List.of())
                        .page(2)
                        .size(5)
                        .totalElements(11)
                        .totalPages(3)
                        .build();
        ArgumentCaptor<TargetProductSearchRequestDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchRequestDTO.class);
        when(targetProductService.getJudgements(any(TargetProductSearchRequestDTO.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/target-products").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        verify(targetProductService).getJudgements(captor.capture());
        assertThat(captor.getValue().getPage()).isEqualTo(2);
        assertThat(captor.getValue().getSize()).isEqualTo(5);
    }

    @Test
    void getJudgementsPassesFilterQueryParamsToService() throws Exception {
        TargetProductJudgementPageDTO page =
                TargetProductJudgementPageDTO.builder()
                        .content(List.of())
                        .page(0)
                        .size(20)
                        .totalElements(0)
                        .totalPages(0)
                        .build();
        ArgumentCaptor<TargetProductSearchRequestDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchRequestDTO.class);
        when(targetProductService.getJudgements(any(TargetProductSearchRequestDTO.class)))
                .thenReturn(page);

        mockMvc.perform(
                        get("/api/admin/target-products")
                                .param("customerName", "홍길동")
                                .param("stockType", "ETF")
                                .param("isTarget", "true"))
                .andExpect(status().isOk());

        verify(targetProductService).getJudgements(captor.capture());
        assertThat(captor.getValue().getCustomerName()).isEqualTo("홍길동");
        assertThat(captor.getValue().getStockType()).isEqualTo(StockType.ETF);
        assertThat(captor.getValue().getIsTarget()).isTrue();
    }

    @Test
    void getJudgementsRejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/admin/target-products").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getJudgementsRejectsNonPositiveSize() throws Exception {
        mockMvc.perform(get("/api/admin/target-products").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    void getJudgementsRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/admin/target-products")).andExpect(status().isUnauthorized());
    }

    @Test
    void getSummaryReturnsTodayAndTotalCountsAsJson() throws Exception {
        TargetProductSummaryDTO summary =
                TargetProductSummaryDTO.builder()
                        .todayJudgementCount(3)
                        .todayTargetCount(2)
                        .todayTargetNetBuyAmount(new BigDecimal("1500000.00"))
                        .totalJudgementCount(50)
                        .build();
        when(targetProductService.getSummary()).thenReturn(summary);

        mockMvc.perform(get("/api/admin/target-products/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayJudgementCount").value(3))
                .andExpect(jsonPath("$.data.todayTargetCount").value(2))
                .andExpect(jsonPath("$.data.todayTargetNetBuyAmount").value(1500000.00))
                .andExpect(jsonPath("$.data.totalJudgementCount").value(50));
    }

    @Test
    @WithAnonymousUser
    void getSummaryRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/admin/target-products/summary")).andExpect(status().isUnauthorized());
    }
}
