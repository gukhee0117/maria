package com.app.maria.domain.community.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.community.service.CommunityWatchlistService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CommunityWatchlistApi.class)
@Import(CustomerSecurityConfig.class)
class CommunityWatchlistApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean CommunityWatchlistService service;
    static final String BASE = "/api/customer/community/watchlist";

    RequestPostProcessor customer() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(52L, "user000052", "이서준"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    void noSessionCannotRead() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void writesRequireCsrf() throws Exception {
        mvc.perform(put(BASE + "/005930").with(customer())).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/005930").with(customer())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminCannotUseApi() throws Exception {
        mvc.perform(get(BASE).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void forgedIdDoesNotOverridePrincipal() throws Exception {
        when(service.list(52L)).thenReturn(List.of("005930"));
        mvc.perform(get(BASE).param("customerId", "51").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("005930"));
        mvc.perform(put(BASE + "/AAPL").param("customerId", "51").with(customer()).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(delete(BASE + "/AAPL").with(customer()).with(csrf()))
                .andExpect(status().isOk());
        verify(service).list(52L);
        verify(service).add(52L, "AAPL");
        verify(service).remove(52L, "AAPL");
    }
}
