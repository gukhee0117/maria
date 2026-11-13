package com.app.maria.domain.fundflow.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.fundflow.dto.FundFlowMaturityDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMaturityMonthDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMaturityResponseDTO;
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
class AdminFundFlowMaturityApiTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AdminFundFlowService adminFundFlowService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("만기 도래 스케줄: 200과 기준일, 묶인 잔액, 월별 목록, 기준 설명을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void maturity() throws Exception {
        when(adminFundFlowService.getMaturity())
                .thenReturn(
                        new FundFlowMaturityResponseDTO(
                                FundFlowMaturityDTO.builder()
                                        .asOfDate(LocalDate.of(2026, 11, 17))
                                        .firstMaturityMonth(LocalDate.of(2027, 3, 1))
                                        .lockedAmount(new BigDecimal("30000000"))
                                        .lockedAccounts(3L)
                                        .maturedRemainingAmount(BigDecimal.ZERO)
                                        .canceledRemainingAmount(new BigDecimal("5601101"))
                                        .canceledAccounts(1L)
                                        .lotsWithoutLeftAmount(0L)
                                        .excludedClosedPaidAmount(new BigDecimal("9000000"))
                                        .months(
                                                List.of(
                                                        FundFlowMaturityMonthDTO.builder()
                                                                .maturityMonth(
                                                                        LocalDate.of(2027, 3, 1))
                                                                .lots(3L)
                                                                .accounts(2L)
                                                                .paidAmount(
                                                                        new BigDecimal("33000000"))
                                                                .remainingAmount(
                                                                        new BigDecimal("20000000"))
                                                                .canceledRemainingAmount(
                                                                        new BigDecimal("5601101"))
                                                                .build()))
                                        .build()));

        mockMvc.perform(get("/api/admin/fund-flow/maturity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("만기 도래 스케줄 조회 성공"))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-11-17"))
                .andExpect(jsonPath("$.data.firstMaturityMonth").value("2027-03-01"))
                .andExpect(jsonPath("$.data.lockedAmount").value(30000000))
                .andExpect(jsonPath("$.data.lockedAccounts").value(3))
                .andExpect(jsonPath("$.data.canceledRemainingAmount").value(5601101))
                .andExpect(jsonPath("$.data.canceledAccounts").value(1))
                .andExpect(jsonPath("$.data.lotsWithoutLeftAmount").value(0))
                .andExpect(jsonPath("$.data.excludedClosedPaidAmount").value(9000000))
                .andExpect(jsonPath("$.data.months[0].maturityMonth").value("2027-03-01"))
                .andExpect(jsonPath("$.data.months[0].lots").value(3))
                .andExpect(jsonPath("$.data.months[0].paidAmount").value(33000000))
                .andExpect(jsonPath("$.data.months[0].remainingAmount").value(20000000))
                .andExpect(jsonPath("$.data.months[0].canceledRemainingAmount").value(5601101))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    @DisplayName("만기 도래 스케줄: 로그인하지 않으면 401을 반환한다")
    void maturityRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/admin/fund-flow/maturity")).andExpect(status().isUnauthorized());
    }
}
