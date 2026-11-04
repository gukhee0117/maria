package com.app.maria.domain.asset.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.asset.service.AssetEventService;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(
        value = CustomerAssetEventApi.class,
        properties = "spring.config.location=optional:classpath:/application-webmvctest.yml")
@Import(CustomerSecurityConfig.class)
class CustomerAssetEventApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean AssetEventService events;

    @Test
    void unauthenticatedRequestsCannotSubscribe() throws Exception {
        mvc.perform(get("/api/customer/assets/events")).andExpect(status().isUnauthorized());
        verifyNoInteractions(events);
    }

    @Test
    void subscriptionUsesPrincipalAndIgnoresCustomerIdParameter() throws Exception {
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().name("connected").data("refresh"));
        when(events.subscribe(7L)).thenReturn(emitter);
        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        new CustomerPrincipal(7L, "user000007", "고객"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        mvc.perform(
                        get("/api/customer/assets/events")
                                .param("customerId", "999")
                                .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        verify(events).subscribe(7L);
        emitter.complete();
    }
}
