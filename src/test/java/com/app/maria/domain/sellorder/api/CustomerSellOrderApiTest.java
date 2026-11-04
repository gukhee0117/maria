package com.app.maria.domain.sellorder.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.service.CustomerSellOrderService;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
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

@WebMvcTest(CustomerSellOrderApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerSellOrderApiTest {
    private static final String SELL_ORDERS = "/api/customer/sell-orders";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerSellOrderService customerSellOrderService;

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
        mockMvc.perform(get(SELL_ORDERS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인한 고객 본인의 매도 내역을 페이지로 준다")
    void returnsOwnSellOrders() throws Exception {
        CustomerSellOrderResponseDTO row = new CustomerSellOrderResponseDTO();
        row.setOrderId(12L);
        row.setTicker("AAPL");
        row.setSellQty(new BigDecimal("3"));
        row.setOrderAmount(new BigDecimal("300"));
        row.setStatus(SellOrderStatus.EXECUTED);
        row.setProcessedAt(LocalDateTime.of(2026, 3, 9, 10, 0));
        when(customerSellOrderService.getSellOrders(7L, 0, 20))
                .thenReturn(PageResponseDTO.of(List.of(row), 1L, 0, 20));

        mockMvc.perform(get(SELL_ORDERS).with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(12))
                .andExpect(jsonPath("$.data.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.content[0].orderAmount").value(300))
                .andExpect(jsonPath("$.data.content[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.content[0].processedAt").value("2026-03-09T10:00:00"));
    }
}
