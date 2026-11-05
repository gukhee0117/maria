package com.app.maria.domain.settlement.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import com.app.maria.domain.settlement.service.CustomerSettlementService;
import com.app.maria.domain.settlement.type.SettlementStatus;
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

@WebMvcTest(CustomerSettlementApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerSettlementApiTest {
    private static final String SETTLEMENTS = "/api/customer/settlements";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerSettlementService customerSettlementService;

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
        mockMvc.perform(get(SETTLEMENTS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인한 고객 본인의 정산 목록을 페이지로 준다")
    void returnsOwnSettlements() throws Exception {
        CustomerSettlementResponseDTO row = new CustomerSettlementResponseDTO();
        row.setExchangeId(3L);
        row.setTicker("AAPL");
        row.setProvisionalAmount(new BigDecimal("297"));
        row.setFinalAt(LocalDateTime.of(2026, 3, 11, 0, 0));
        row.setSettlementStatus(SettlementStatus.PROVISIONAL);
        when(customerSettlementService.getSettlements(7L, 0, 20))
                .thenReturn(PageResponseDTO.of(List.of(row), 1L, 0, 20));

        mockMvc.perform(get(SETTLEMENTS).with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(3))
                .andExpect(jsonPath("$.data.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.content[0].settlementStatus").value("PROVISIONAL"))
                .andExpect(jsonPath("$.data.content[0].finalAt").value("2026-03-11T00:00:00"))
                .andExpect(jsonPath("$.data.content[0].finalAmount").doesNotExist());
    }
}
