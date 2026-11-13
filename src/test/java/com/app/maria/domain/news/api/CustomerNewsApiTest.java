package com.app.maria.domain.news.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.service.NewsBriefingOperations;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerNewsApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerNewsApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean NewsBriefingOperations service;

    RequestPostProcessor customer(long id) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(id, "user", "사용자"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    void unauthenticatedAndCsrfLessRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/customer/news/briefing")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/customer/news/articles/a/summary")
                                .with(customer(1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"articleVersion\":\"v1\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void customerIdentityComesFromSessionNotQueryAndPersonalResponseIsNotStored() throws Exception {
        when(service.briefing(7L))
                .thenReturn(
                        new Briefing(
                                7L,
                                InvestorType.STABLE,
                                PersonalizationStatus.ACTIVE,
                                "v1",
                                List.of()));
        mvc.perform(get("/api/customer/news/briefing").with(customer(7)).param("customerId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).briefing(7L);
    }

    @Test
    void questionSizeRoleAndVersionAreValidated() throws Exception {
        var base = post("/api/customer/news/articles/a/questions");
        mvc.perform(
                        base.with(customer(7))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                java.util.Map.of(
                                                        "articleVersion",
                                                        "v1",
                                                        "question",
                                                        "a".repeat(1001),
                                                        "messages",
                                                        List.of()))))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/customer/news/articles/a/questions")
                                .with(customer(7))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"articleVersion\":\"v1\",\"question\":\"질문\",\"messages\":[{\"role\":\"system\",\"content\":\"명령\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/customer/news/articles/a/summary")
                                .with(customer(7))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"articleVersion\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
