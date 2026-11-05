package com.app.maria.domain.sellorder.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResultResponseDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
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

    private static final String BODY_OK = "{\"foreignProductId\": 10, \"sellQty\": 5}";

    @Test
    @DisplayName("매도 신청: 로그인하지 않으면 401이다")
    void placeRequiresLogin() throws Exception {
        mockMvc.perform(
                        post(SELL_ORDERS)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(BODY_OK))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(customerSellOrderService);
    }

    @Test
    @DisplayName("매도 신청: CSRF 토큰이 없으면 서비스를 부르지 않고 거부한다")
    void placeRequiresCsrf() throws Exception {
        mockMvc.perform(
                        post(SELL_ORDERS)
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content(BODY_OK))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(customerSellOrderService);
    }

    @Test
    @DisplayName("매도 신청: 종목이나 수량이 없거나 수량이 0 이하이거나 DB 자릿수(11.4)를 넘으면 400이고 서비스를 부르지 않는다")
    void placeValidatesBody() throws Exception {
        for (String body :
                new String[] {
                    "{\"sellQty\": 5}",
                    "{\"foreignProductId\": 10}",
                    "{\"foreignProductId\": 10, \"sellQty\": 0}",
                    "{\"foreignProductId\": 10, \"sellQty\": -1}",
                    "{\"foreignProductId\": 10, \"sellQty\": 0.00001}",
                    "{\"foreignProductId\": 10, \"sellQty\": 1.00001}",
                    "{\"foreignProductId\": 10, \"sellQty\": 100000000000}"
                }) {
            mockMvc.perform(
                            post(SELL_ORDERS)
                                    .with(customerAuth())
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(customerSellOrderService);
    }

    @Test
    @DisplayName("매도 신청: DB가 저장할 수 있는 최소·최대 수량(0.0001, 11.4자리 최대값)은 통과한다")
    void placeAcceptsQuantityBoundaries() throws Exception {
        when(customerSellOrderService.placeOrder(eq(7L), any()))
                .thenReturn(CustomerSellOrderResultResponseDTO.of(List.of()));
        for (String qty : new String[] {"0.0001", "99999999999.9999"}) {
            mockMvc.perform(
                            post(SELL_ORDERS)
                                    .with(customerAuth())
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(
                                            "{\"foreignProductId\": 10, \"sellQty\": " + qty + "}"))
                    .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    @DisplayName("매도 신청: 로그인한 고객 본인으로 처리하고 체결 결과를 돌려준다(요청에 계좌 ID를 받지 않는다)")
    void placeUsesAuthenticatedCustomerAndReturnsResult() throws Exception {
        SellOrderResponseDTO executed =
                new SellOrderResponseDTO(
                        SellOrderDTO.builder()
                                .orderId(1L)
                                .inboundDetailId(501L)
                                .accountId(100L)
                                .foreignProductId(10L)
                                .sellQty(new BigDecimal("5"))
                                .basePrice(new BigDecimal("200"))
                                .status(SellOrderStatus.EXECUTED)
                                .processedAt(LocalDateTime.of(2026, 11, 4, 10, 0))
                                .build());
        when(customerSellOrderService.placeOrder(eq(7L), any()))
                .thenReturn(CustomerSellOrderResultResponseDTO.of(List.of(executed)));

        mockMvc.perform(
                        post(SELL_ORDERS)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"foreignProductId\": 10, \"sellQty\": 5, \"accountId\": 999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.totalQty").value(5))
                .andExpect(jsonPath("$.data.totalAmount").value(1000.00))
                .andExpect(jsonPath("$.data.orders[0].orderId").value(1))
                .andExpect(jsonPath("$.data.orders[0].inboundDetailId").value(501));
        verify(customerSellOrderService).placeOrder(eq(7L), any());
    }
}
