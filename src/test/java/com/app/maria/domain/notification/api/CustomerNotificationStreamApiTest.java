package com.app.maria.domain.notification.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.notification.service.CustomerNotificationStream;
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

@WebMvcTest(CustomerNotificationStreamApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerNotificationStreamApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerNotificationStream stream;

    @Test
    void streamRequiresCustomerSession() throws Exception {
        mvc.perform(get("/api/customer/notifications/stream")).andExpect(status().isUnauthorized());
        verifyNoInteractions(stream);
    }

    @Test
    void streamUsesSessionCustomerNotQueryCustomerId() throws Exception {
        when(stream.subscribe(
                        org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SseEmitter());
        mvc.perform(
                        get("/api/customer/notifications/stream?customerId=999")
                                .with(
                                        authentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        new CustomerPrincipal(1L, "customer", "고객"),
                                                        null,
                                                        List.of(
                                                                new SimpleGrantedAuthority(
                                                                        "ROLE_CUSTOMER"))))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(request().asyncStarted());
        verify(stream)
                .subscribe(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
        verify(stream, never())
                .subscribe(
                        org.mockito.ArgumentMatchers.eq(999L), org.mockito.ArgumentMatchers.any());
    }
}
