package com.app.maria.domain.domestic.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.domestic.dto.response.CustomerDomesticTradeResponseDTO;
import com.app.maria.domain.domestic.service.CustomerDomesticTradeService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.response.PageResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerDomesticTradeApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerDomesticTradeApiTest {
    private static final String DOMESTIC_TRADES = "/api/customer/domestic-trades";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerDomesticTradeService customerDomesticTradeService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(DOMESTIC_TRADES)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인한 고객 본인의 국내 매매 체결 내역을 페이지로 준다")
    void returnsOwnTrades() throws Exception {
        CustomerDomesticTradeResponseDTO row =
                new CustomerDomesticTradeResponseDTO(
                        LocalDateTime.of(2026, 11, 3, 10, 0),
                        "005930",
                        "삼성전자",
                        "BUY",
                        new BigDecimal("10"),
                        new BigDecimal("70000"),
                        new BigDecimal("700000"));
        when(customerDomesticTradeService.getTrades(7L, 0, 20))
                .thenReturn(PageResponseDTO.of(List.of(row), 1L, 0, 20));

        mockMvc.perform(get(DOMESTIC_TRADES).with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.content[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.data.content[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.data.content[0].tradeType").value("BUY"))
                .andExpect(jsonPath("$.data.content[0].amount").value(700000))
                .andExpect(jsonPath("$.data.content[0].executedAt").value("2026-11-03T10:00:00"));
    }
}
