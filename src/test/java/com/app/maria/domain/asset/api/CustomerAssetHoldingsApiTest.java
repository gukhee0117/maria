package com.app.maria.domain.asset.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.asset.dto.response.CustomerAssetHoldingResponseDTO;
import com.app.maria.domain.asset.service.CustomerAssetHoldingsService;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.global.config.CustomerSecurityConfig;
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

@WebMvcTest(
        value = CustomerAssetHoldingsApi.class,
        properties = "spring.config.location=optional:classpath:/application-webmvctest.yml")
@Import(CustomerSecurityConfig.class)
class CustomerAssetHoldingsApiTest {
    private static final String URL = "/api/customer/assets/holdings";

    @Autowired MockMvc mockMvc;
    @MockitoBean CustomerAssetHoldingsService service;

    private RequestPostProcessor auth(String role) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "customer@test.com", "테스트고객"),
                        null,
                        List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이고 서비스를 부르지 않는다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("관리자 역할은 고객 보유 종목을 읽을 수 없다")
    void adminRoleIsForbidden() throws Exception {
        mockMvc.perform(get(URL).with(auth("ROLE_ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("로그인한 고객 본인의 보유 종목을 주고 시세가 없는 칸은 null로 보존한다")
    void returnsOwnHoldingsKeepingNulls() throws Exception {
        when(service.getHoldings(7L))
                .thenReturn(
                        List.of(
                                new CustomerAssetHoldingResponseDTO(
                                        "005930",
                                        "삼성전자",
                                        "반도체",
                                        Type.STOCK,
                                        DomesticStockStatus.HOLDING,
                                        new BigDecimal("10"),
                                        new BigDecimal("10"),
                                        new BigDecimal("70000"),
                                        new BigDecimal("700000.00"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        3,
                                        null,
                                        List.of(
                                                "currentPrice",
                                                "evaluationAmount",
                                                "profit",
                                                "profitRate"))));

        mockMvc.perform(get(URL).param("accountId", "999").with(auth("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ticker").value("005930"))
                .andExpect(jsonPath("$.data[0].sellableQty").value(10))
                .andExpect(jsonPath("$.data[0].purchaseAmount").value(700000.00))
                .andExpect(jsonPath("$.data[0].currentPrice").doesNotExist())
                .andExpect(jsonPath("$.data[0].riskGrade").value(3))
                .andExpect(jsonPath("$.data[0].unavailableFields[0]").value("currentPrice"));
        verify(service).getHoldings(7L);
    }
}
