package com.app.maria.domain.stockpackage.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.stockpackage.dto.response.StockPriceHistoryResponseDTO;
import com.app.maria.domain.stockpackage.service.StockPriceHistoryService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@WebMvcTest(StockPriceHistoryApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class StockPriceHistoryApiTest {
    private static final String PATH = "/api/customer/stock-packages/price-history";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private StockPriceHistoryService service;

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
        mockMvc.perform(get(PATH).param("tickers", "005930")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("days를 안 주면 30일로 조회하고 종목·날짜·종가를 준다")
    void defaultsToThirtyDays() throws Exception {
        when(service.getHistory(List.of("005930", "000660"), 30))
                .thenReturn(
                        List.of(
                                new StockPriceHistoryResponseDTO(
                                        "005930",
                                        true,
                                        List.of(
                                                new StockPriceHistoryResponseDTO.Point(
                                                        LocalDate.of(2026, 11, 17),
                                                        new BigDecimal("71000")))),
                                StockPriceHistoryResponseDTO.unavailable("000660")));

        mockMvc.perform(get(PATH).param("tickers", "005930,000660").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("종가 이력 조회 성공"))
                .andExpect(jsonPath("$.data[0].ticker").value("005930"))
                .andExpect(jsonPath("$.data[0].available").value(true))
                .andExpect(jsonPath("$.data[0].points[0].date").value("2026-11-17"))
                .andExpect(jsonPath("$.data[0].points[0].close").value(71000))
                .andExpect(jsonPath("$.data[1].available").value(false))
                .andExpect(jsonPath("$.data[1].points.length()").value(0));
        verify(service).getHistory(List.of("005930", "000660"), 30);
    }

    @Test
    @DisplayName("days를 주면 그대로 서비스에 넘긴다(범위 보정은 서비스에서)")
    void passesDays() throws Exception {
        when(service.getHistory(List.of("005930"), 20)).thenReturn(List.of());

        mockMvc.perform(
                        get(PATH)
                                .param("tickers", "005930")
                                .param("days", "20")
                                .with(customerAuth()))
                .andExpect(status().isOk());
        verify(service).getHistory(List.of("005930"), 20);
    }

    @Test
    @DisplayName("종목 코드가 올바르지 않으면 400이다")
    void invalidTickersAre400() throws Exception {
        when(service.getHistory(List.of("abc"), 30))
                .thenThrow(new AppException(ErrorType.STOCK_PACKAGE_QUOTE_INVALID));

        mockMvc.perform(get(PATH).param("tickers", "abc").with(customerAuth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("tickers가 없으면 400이다")
    void missingTickersAre400() throws Exception {
        mockMvc.perform(get(PATH).with(customerAuth())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
