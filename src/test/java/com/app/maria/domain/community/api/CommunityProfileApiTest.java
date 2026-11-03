package com.app.maria.domain.community.api;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.community.service.CommunityProfileService;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
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

@WebMvcTest(CommunityProfileApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CommunityProfileApiTest {
    private static final String PUBLIC = "/api/customer/profile/investor-type-public";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommunityProfileService service;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void unauthenticated() throws Exception {
        mockMvc.perform(
                        put(PUBLIC)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"investorTypePublic\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 토큰 없이 바꾸면 403이고 서비스를 부르지 않는다")
    void withoutCsrf() throws Exception {
        mockMvc.perform(
                        put(PUBLIC)
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content("{\"investorTypePublic\":true}"))
                .andExpect(status().isForbidden());

        verify(service, never()).changeInvestorTypePublic(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("성향 공개를 켜면 로그인한 고객 ID로 저장하고 결과를 돌려준다")
    void changeInvestorTypePublic() throws Exception {
        when(service.changeInvestorTypePublic(7L, true)).thenReturn(true);

        mockMvc.perform(
                        put(PUBLIC)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"investorTypePublic\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.investorTypePublic").value(true));
        mockMvc.perform(
                        put(PUBLIC)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service).changeInvestorTypePublic(7L, true);
    }
}
