package com.app.maria.domain.news.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.service.*;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerNewsThemeApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerNewsThemeApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean NewsCustomerContextService contexts;
    @MockitoBean NewsThemeService themes;

    @Test
    void themesRequireLoginAndNeverAcceptAnotherCustomerId() throws Exception {
        mvc.perform(get("/api/customer/news/themes")).andExpect(status().isUnauthorized());
        var auth =
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "user7", "고객"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        when(contexts.load(7L))
                .thenReturn(
                        new Context(
                                7L,
                                Set.of("005930", "035420"),
                                InvestorType.STABLE,
                                PersonalizationStatus.ACTIVE,
                                Map.of("005930", "삼성전자", "035420", "NAVER")));
        when(themes.themes(anySet())).thenReturn(Map.of("005930", Set.of("반도체")));
        mvc.perform(
                        get("/api/customer/news/themes")
                                .param("customerId", "999")
                                .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].ticker").value("005930"))
                .andExpect(jsonPath("$.data[0].themes[0]").value("반도체"))
                .andExpect(jsonPath("$.data[1].themes").isEmpty());
        verify(contexts).load(7L);
        verify(contexts, never()).load(999L);
    }
}
