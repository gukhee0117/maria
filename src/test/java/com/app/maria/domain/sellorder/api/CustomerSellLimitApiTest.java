package com.app.maria.domain.sellorder.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.sellorder.dto.response.SellLimitStatusDTO;
import com.app.maria.domain.sellorder.service.SellLimitService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
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

@WebMvcTest(CustomerSellLimitApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerSellLimitApiTest {
    private static final String SELL_LIMIT = "/api/customer/sell-limit";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SellLimitService sellLimitService;

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
        mockMvc.perform(get(SELL_LIMIT)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인한 고객 본인의 한도·누적·남은 한도를 준다")
    void returnsOwnLimit() throws Exception {
        when(sellLimitService.getStatusByCustomerId(7L))
                .thenReturn(
                        new SellLimitStatusDTO(
                                new BigDecimal("30000000"),
                                new BigDecimal("12000000"),
                                new BigDecimal("18000000")));

        mockMvc.perform(get(SELL_LIMIT).with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limitAmount").value(30000000))
                .andExpect(jsonPath("$.data.usedAmount").value(12000000))
                .andExpect(jsonPath("$.data.remainingLimit").value(18000000));
    }

    @Test
    @DisplayName("거래할 수 없는 계좌면 409다")
    void rejectsAccountThatCannotTrade() throws Exception {
        when(sellLimitService.getStatusByCustomerId(7L))
                .thenThrow(new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED));

        mockMvc.perform(get(SELL_LIMIT).with(customerAuth())).andExpect(status().isConflict());
    }
}
