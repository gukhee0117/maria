package com.app.maria.domain.fundflow.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.fundflow.dto.FundFlowFunnelDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelStageDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.service.AdminFundFlowService;
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

@WebMvcTest(AdminFundFlowApi.class)
@Import(SecurityConfig.class)
class AdminFundFlowApiTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AdminFundFlowService adminFundFlowService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("퍼널을 조회하면 200과 기준일, 6단계 목록, 분모 문구를 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getFunnelReturns200WithStages() throws Exception {
        when(adminFundFlowService.getFunnel(any()))
                .thenReturn(
                        new FundFlowFunnelResponseDTO(
                                FundFlowFunnelDTO.builder()
                                        .asOfDate(LocalDate.of(2026, 11, 17))
                                        .stages(
                                                List.of(
                                                        FundFlowFunnelStageDTO.builder()
                                                                .stageNo(1)
                                                                .stage("웹 가입")
                                                                .customers(95050L)
                                                                .convTotal(new BigDecimal("1.0000"))
                                                                .build(),
                                                        FundFlowFunnelStageDTO.builder()
                                                                .stageNo(2)
                                                                .stage("마이데이터 동의")
                                                                .customers(85996L)
                                                                .convPrev(new BigDecimal("0.9047"))
                                                                .convTotal(new BigDecimal("0.9047"))
                                                                .build()))
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("가입→첫 매수 퍼널 조회 성공"))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-11-17"))
                .andExpect(jsonPath("$.data.stages[0].stageNo").value(1))
                .andExpect(jsonPath("$.data.stages[0].customers").value(95050))
                .andExpect(jsonPath("$.data.stages[0].convPrev").doesNotExist())
                .andExpect(jsonPath("$.data.stages[1].convPrev").value(0.9047))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("인증되지 않은 요청이면 401을 반환하고 서비스는 호출되지 않는다")
    void getFunnelReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/fund-flow/funnel")).andExpect(status().isUnauthorized());

        verify(adminFundFlowService, never()).getFunnel(any());
    }
}
