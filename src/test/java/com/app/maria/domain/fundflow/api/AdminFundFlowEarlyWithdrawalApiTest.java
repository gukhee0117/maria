package com.app.maria.domain.fundflow.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalCauseDTO;
import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowEarlyWithdrawalResponseDTO;
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
class AdminFundFlowEarlyWithdrawalApiTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AdminFundFlowService adminFundFlowService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("1년 미경과 원금 인출: 200과 분모·분자·비율·원인별 목록·기준 설명을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void earlyWithdrawal() throws Exception {
        when(adminFundFlowService.getEarlyWithdrawal())
                .thenReturn(
                        new FundFlowEarlyWithdrawalResponseDTO(
                                FundFlowEarlyWithdrawalDTO.builder()
                                        .asOfDate(LocalDate.of(2026, 11, 17))
                                        .finalizedAccounts(75046L)
                                        .finalizedAmount(new BigDecimal("6026600000000"))
                                        .earlyAccounts(16L)
                                        .earlyWithdrawnAmount(new BigDecimal("101362973"))
                                        .accountShare(new BigDecimal("0.0002"))
                                        .amountShare(new BigDecimal("0.0003"))
                                        .byCause(
                                                List.of(
                                                        FundFlowEarlyWithdrawalCauseDTO.builder()
                                                                .cause("CLOSURE_FORCED")
                                                                .label("해지 강제 인출")
                                                                .accounts(15L)
                                                                .amount(new BigDecimal("95000000"))
                                                                .build(),
                                                        FundFlowEarlyWithdrawalCauseDTO.builder()
                                                                .cause("OTHER")
                                                                .label("그 밖의 인출")
                                                                .accounts(1L)
                                                                .amount(new BigDecimal("6362973"))
                                                                .build()))
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/early-withdrawal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("1년 미경과 원금 인출 조회 성공"))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-11-17"))
                .andExpect(jsonPath("$.data.finalizedAccounts").value(75046))
                .andExpect(jsonPath("$.data.earlyAccounts").value(16))
                .andExpect(jsonPath("$.data.earlyWithdrawnAmount").value(101362973))
                .andExpect(jsonPath("$.data.accountShare").value(0.0002))
                .andExpect(jsonPath("$.data.amountShare").value(0.0003))
                .andExpect(jsonPath("$.data.byCause[0].cause").value("CLOSURE_FORCED"))
                .andExpect(jsonPath("$.data.byCause[0].label").value("해지 강제 인출"))
                .andExpect(jsonPath("$.data.byCause[1].cause").value("OTHER"))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("1년 미경과 원금 인출: 로그인하지 않으면 401을 반환한다")
    void earlyWithdrawalRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/admin/fund-flow/early-withdrawal"))
                .andExpect(status().isUnauthorized());
    }
}
